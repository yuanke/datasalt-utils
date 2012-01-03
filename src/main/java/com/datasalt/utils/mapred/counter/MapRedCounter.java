/**
 * Copyright [2011] [Datasalt Systems S.L.]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.datasalt.utils.mapred.counter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.Reducer;

import org.apache.hadoop.mapreduce.lib.input.MultipleInputs;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.thrift.TBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datasalt.utils.commons.HadoopUtils;
import com.datasalt.utils.io.LongPairWritable;
import com.datasalt.utils.io.Serialization;
import com.datasalt.utils.mapred.BaseMapper;
import com.datasalt.utils.mapred.counter.io.CounterDistinctKey;
import com.datasalt.utils.mapred.counter.io.CounterKey;
import com.datasalt.utils.mapred.counter.io.CounterValue;

/**
 * MapReduce Job for efficiently compute counts, distinct count, and lists of distinct elements. It is efficient,
 * because the counts are done in just one MapReduce job, and a combiner is used for reducing the network load. This job
 * performs something similar to the following two SQL Queries:<br/>
 * 1 - "SELECT count(item), count(distinct item) FROM input GROUP BY typeIdentifier, group"<br/>
 * 2 - "SELECT item, count(*) FROM input GROUP BY typeIdentifier, group, item"<br/>
 * The result of the 1 query is in the file {@link Outputs#COUNTDISTINCTFILE} and the result of the 2 query is the file
 * {@link Outputs#COUNTFILE} <br/>
 * Instances of the Job can be created through the method
 * {@link #buildMapRedCounterJob(String, Class, String, Configuration)}. For perform counts over an input file, one or
 * more {@link MapRedCounterMapper} must be added through the method {@link #addInput(Job, Path, Class, Class)}. Each
 * {@link MapRedCounterMapper} can emit one or more items to be counted via the method
 * {@link MapRedCounterMapper#emit(int, Object, Object)}. Each items belongs to a typeIdentifier and a group. The
 * output generated by this job are two files with the following information each:<br/>
 * - {@link Outputs#COUNTFILE} [typeIdentifier, group, item] -> count <br/>
 * - {@link Outputs#COUNTDISTINCTFILE} [typeIdentifier, group] -> [count, distinctItemsCount] <br/>
 * <br/>
 * The data types for the files will be:<br/>
 * - {@link Outputs#COUNTFILE} {@link CounterKey} -> {@link LongWritable} <br/>
 * - {@link Outputs#COUNTDISTINCTFILE} {@link CounterDistinctKey} -> {@link LongPairWritable} <br/>
 * <br/>
 * The list of distinct elements for each group can be found in the file {@link Outputs#COUNTFILE}. <br/>
 * This job supports any datatype that can be serialized, deserialized to bytes.{@link Writable} and {@link TBase} are
 * integrated, but any other serialization is allowed if can serialize to bytes. But the serialization mechanism must
 * support the following contract:<br/>
 * 1. If A.equals(B) then ser(A).equals(ser(B))<br/>
 * 2. If ser(A).equals(ser(B)) then deser(ser(A)).equals(deser(ser(B))<br/>
 * That is because this jobs perform comparison at the level of bytes, so two objects are considered the same if both
 * serializes to the same bytes.
 * 
 * @author ivan
 */
public class MapRedCounter {

	final static Logger log = LoggerFactory.getLogger(MapRedCounter.class);

	/*
	 * Configuration properties with this prefix are used for having minimum counts > 1 E.g. count items for this group
	 * only if there is at least 4 counts for each individual item. This is configured with property PREFIX + GROUP_ID
	 */
	public final static String MINIMUM_COUNT_FOR_GROUP_CONF_PREFIX = MapRedCounter.class.getName()
	    + ".minimum.count.for.group.";

	public enum Outputs {
		COUNTFILE, // This file will contains the count per each item, and the list of distints for a group
		COUNTDISTINCTFILE
		// This file contains the total count and total distinct count for a group
	}

	public enum Counters {
		INPUT_PAIRS, // Number of input pairs [group, item, count] to be counted.
		INPUT_PAIRS_TOTAL_COUNT, // Sum(count) for each input pairs [group, item].
		OUT_NUM_GROUPS, // The total number of distinct groups
		OUT_NUM_ITEMS, // The total number of distinct [group,item]
		OUT_TOTAL_ITEMS, // The total number an item is called to be counted
		OUT_TOTAL_DISTINCTS
		// The aggregated sum of the distinct counts
	}

	/**
	 * Mapper to be extended by the user. It receives [K] -> [V] and emits [PairDatumWithIdentifierRawComparable] ->
	 * [PairDatumRawComparable<?,LongWritable>]
	 * 
	 * @author ivan
	 */
	public static abstract class MapRedCounterMapper<INPUT_KEY, INPUT_VALUE> extends
	    BaseMapper<INPUT_KEY, INPUT_VALUE, CounterKey, CounterValue> implements CountEmitInterface {
		CounterKey key = new CounterKey();
		Context context;

		/*
		 * We have one set of instances that we use for the most common case: counting 1 This way we can reuse the already
		 * serialized bytes each time we count 1
		 */
		CounterValue one = new CounterValue();
		LongWritable numberOne = new LongWritable(1);

		/*
		 * ... And we have another set of instances for counting arbitrary times. This instance's number will be serialized
		 * each time to acomodate the arbitrary count provided by the user.
		 */
		CounterValue arbitrary = new CounterValue();
		LongWritable arbitraryNumber = new LongWritable(1);

		// Serialization ser;

		@Override
		protected void setup(Context context) throws IOException, InterruptedException {
			super.setup(context);
			this.context = context;
			// ser = new Serialization(context.getConfiguration());
			one.setCount(ser.ser(numberOne));
		}

		/**
		 * To be implemented by the user of that class. Inside that method, you have to call
		 * {@link #emit(int, Object, Object)} to emit the groups and items than then will be counted.
		 */
		@Override
		protected abstract void map(INPUT_KEY key, INPUT_VALUE value, Context context) throws IOException,
		    InterruptedException;

		/**
		 * Emits a new Item to be counted. After the execution of the counter will be present the following stats:<br/>
		 * [typeIdentifier, group, item] -> count <br/>
		 * [typeIdentifier, group] -> count, distinctItemsCount<br/>
		 * <br/>
		 * Also the list of distinct items per group will exist in a file.<br/>
		 * The typeIdentifier is there to be used for identifying the types of the group and the item. Because in the same
		 * file will be present counts for different groups and items that will maybe be of different types, this number can
		 * be used to identify to which one it belongs.
		 */
		public void emit(int typeIdentifier, Object group, Object item) throws IOException, InterruptedException {
			count(typeIdentifier, 1);
			key.setGroupId(typeIdentifier);
			key.setGroup(ser.ser(group));
			key.setItem(ser.ser(item));
			one.setItem(ser.ser(item));
			context.write(key, one);
		}

		/**
		 * Same as above, but you can specify a number of times to be counted different from 1. We need this method,
		 * otherwise we would need to do for(..;..;..) { emit() } which causes extremely inefficient performance since the
		 * Map output size is increased lineraly.
		 * 
		 * @param typeIdentifier
		 * @param group
		 * @param item
		 * @param times
		 * @throws IOException
		 * @throws InterruptedException
		 */
		public void emit(int typeIdentifier, Object group, Object item, long times) throws IOException,
		    InterruptedException {
			count(typeIdentifier, times);
			key.setGroupId(typeIdentifier);
			key.setGroup(ser.ser(group));
			key.setItem(ser.ser(item));
			arbitrary.setItem(ser.ser(item));
			arbitraryNumber.set(times);
			arbitrary.setCount(ser.ser(arbitraryNumber));
			context.write(key, arbitrary);
		}

		private void count(int typeIdentifier, long times) {
			String hadoopCounter = "aggType-" + typeIdentifier;
			context.getCounter(hadoopCounter, Counters.INPUT_PAIRS + "").increment(1);
			context.getCounter(hadoopCounter, Counters.INPUT_PAIRS_TOTAL_COUNT + "").increment(times);
		}
	}

	/**
	 * Receives [typeIdentifier, group, item] -> [item, count]^* and emits [typeIdentifier, group, item] -> [item, count]
	 * 
	 * This reducer receives all the items for a given typeIdentifier and group, counts the number of items, aggregating
	 * it at the output. Several rows for the same reducer can be received, but only one with the aggregated value is
	 * emited. That is used as combiner of the {@link MapRedCounter} for reducing the network comunication by reducing the
	 * amount of information going through the network.
	 * 
	 * @author ivan
	 */
	public static class MapRedCountCombiner extends Reducer<CounterKey, CounterValue, CounterKey, CounterValue> {

		final static Logger log = LoggerFactory.getLogger(MapRedCountCombiner.class);
		LongWritable count = new LongWritable();
		CounterValue outputValue = new CounterValue();
		LongWritable finalCountWritable = new LongWritable();

		Serialization ser;

		@Override
		protected void setup(Context context) throws IOException, InterruptedException {
			super.setup(context);
			ser = new Serialization(context.getConfiguration());
		}

		@Override
		protected void reduce(CounterKey key, Iterable<CounterValue> partialCounts, Context ctx) throws IOException,
		    InterruptedException {

			long finalCount = 0;

			for(CounterValue partialCount : partialCounts) {
				ser.deser(count, partialCount.getCount());
				finalCount += count.get();
			}

			// Finally we emit as value the item and the count for that item.
			finalCountWritable.set(finalCount);
			outputValue.setItem(key.getItem());
			outputValue.setCount(ser.ser(finalCountWritable));

			ctx.write(key, outputValue);
		}
	}

	/**
	 * This reducer receives all the items for a given typeIdentifier and group, sorted by items, and performs the counts
	 * for each item, and the count distinct.
	 * 
	 * Receives [typeIdentifier, group] -> [item, count]^* secondary sorted by item, and emits 2 files <br/>
	 * - {@link Outputs#COUNTFILE} [typeIdentifier, group, item] -> count <br/>
	 * - {@link Outputs#COUNTDISTINCTFILE} [typeIdentifier, group] -> [count, distinctItemsCount] <br/>
	 * <br/>
	 * The data types for the files will be:<br/>
	 * - {@link Outputs#COUNTFILE} {@link CounterKey} -> {@link LongWritable} <br/>
	 * - {@link Outputs#COUNTDISTINCTFILE} {@link CounterDistinctKey} -> {@link LongPairWritable} <br/>
	 * 
	 * @author ivan
	 */
	@SuppressWarnings("rawtypes")
	public static class MapRedCountReducer extends Reducer<CounterKey, CounterValue, NullWritable, NullWritable> {

		final static Logger log = LoggerFactory.getLogger(MapRedCountReducer.class);

		MultipleOutputs mos;

		LongWritable currentItemCount = new LongWritable();

		// Outputs
		LongWritable itemResult = new LongWritable();
		LongPairWritable totalResults = new LongPairWritable();

		CounterKey countFileKey = new CounterKey();
		CounterDistinctKey countDistinctFileKey = new CounterDistinctKey();
		BytesWritable itemGroupSignature = new BytesWritable();
		Serialization ser;

		/*
		 * May contain minimum counts for accounting for certain groups. E.g. count items only if there is at least 2
		 * ocurrences for each item.
		 */
		Map<Integer, Integer> minimumCountForGroup = new HashMap<Integer, Integer>();

		@SuppressWarnings("unchecked")
		protected void setup(Context context) throws IOException, InterruptedException {
			super.setup(context);
			mos = new MultipleOutputs(context);
			ser = new Serialization(context.getConfiguration());
			// Iterate over the configuration to see if there is any minimum count configured for certain groups
			for(Map.Entry<String, String> entry : context.getConfiguration()) {
				String configurationKey = entry.getKey();
				if(configurationKey.startsWith(MINIMUM_COUNT_FOR_GROUP_CONF_PREFIX)) {
					int groupId = Integer.parseInt(configurationKey.substring(MINIMUM_COUNT_FOR_GROUP_CONF_PREFIX.length(),
					    configurationKey.length()));
					minimumCountForGroup.put(groupId, Integer.parseInt(entry.getValue()));
				}
			}
		}

		/**
		 * Because we may have configurable minimum count per group, we need to check if the condition is met.
		 * 
		 * @param groupId
		 * @param itemCount
		 * 
		 */
		protected boolean meetsMinimumItemCountForThisGroup(int groupId, long itemCount) {
			Integer minimumCount = minimumCountForGroup.get(groupId);
			if(minimumCount == null) {
				minimumCount = 1;
			}
			if(itemCount >= minimumCount) {
				return true;
			} else {
				return false;
			}
		}

		protected void cleanup(Context context) throws IOException, InterruptedException {
			mos.close();
		};

		@SuppressWarnings("unchecked")
		@Override
		protected void reduce(CounterKey key, Iterable<CounterValue> partialCounts, Context ctx) throws IOException,
		    InterruptedException {
			String hadoopCounter = "aggType-" + key.getGroupId();

			// We set the values that won't change during this reduce
			countFileKey.setGroupId(key.getGroupId());
			countFileKey.setGroup(key.getGroup());

			// Different counters and state variables
			long totalCount = 0;
			long distinctCount = 0;
			long itemCount = 0;
			boolean first = true;

			/*
			 * The item for current group of items. Because items will come sorted, we can have more than one item, one after
			 * each other. We identified that group by the content of one of them, because all will have the same.
			 */

			// Comparator c = new BytesWritable.Comparator();
			for(CounterValue currentItemInfo : partialCounts) {
				ser.deser(currentItemCount, currentItemInfo.getCount());
				BytesWritable currentItem = currentItemInfo.getItem();

				// First item distinct group
				if(first) {
					first = false;
					itemGroupSignature.set(currentItemInfo.getItem());

				// Different item distinct group starts
				} else if(currentItem.compareTo(itemGroupSignature) != 0) {

					// So we have the total Count for that item and clean it up
					itemResult.set(itemCount);
					
					if(meetsMinimumItemCountForThisGroup(key.getGroupId(), itemCount)) {

						totalCount += itemCount;
						// We are going to emit the number of occurrences of an item on this group
						countFileKey.setItem(itemGroupSignature);
						mos.write(Outputs.COUNTFILE + "", countFileKey, itemResult);
						ctx.getCounter(hadoopCounter, Counters.OUT_TOTAL_ITEMS + "").increment(itemResult.get());
						ctx.getCounter(hadoopCounter, Counters.OUT_NUM_ITEMS + "").increment(1);

						// The new signature is set
						itemGroupSignature.set(currentItem);

						// We have to count a new distinct, then
						distinctCount++;
					}
					
					itemCount = 0;
				}

				// In any case, we have to count the number of occurrences for each item
				itemCount += currentItemCount.get();
			}

			// At that point, we still have to close the latest group
			// We are going to emit the number of occurrences of an item on this group
			if(meetsMinimumItemCountForThisGroup(key.getGroupId(), itemCount)) {
				itemResult.set(itemCount);
				totalCount += itemCount;

				countFileKey.setItem(itemGroupSignature);
				mos.write(Outputs.COUNTFILE + "", countFileKey, itemResult);
				ctx.getCounter(hadoopCounter, Counters.OUT_TOTAL_ITEMS + "").increment(itemResult.get());
				ctx.getCounter(hadoopCounter, Counters.OUT_NUM_ITEMS + "").increment(1);
				distinctCount++;
			}
	
			if(totalCount > 0) {
				// Now we emit the totalCount and the totalDistinctCount
				countDistinctFileKey.setGroupId(key.getGroupId());
				countDistinctFileKey.setGroup(key.getGroup());
				totalResults.setValue1(totalCount);
				totalResults.setValue2(distinctCount);
				ctx.getCounter(hadoopCounter, Counters.OUT_TOTAL_DISTINCTS + "").increment(distinctCount);
				ctx.getCounter(hadoopCounter, Counters.OUT_NUM_GROUPS + "").increment(1);
				mos.write(Outputs.COUNTDISTINCTFILE + "", countDistinctFileKey, totalResults);
			}
		}
	}

	/**
	 * Builds a MapRedCounterJob that counts the number items occurrences per each item, the number of distinct items per
	 * group and the total Occurrences of each item per group. Then you can add more mappers to that class by calling
	 * #addInput()
	 */
	public static Job buildMapRedCounterJob(String name,
	    @SuppressWarnings("rawtypes") Class<? extends OutputFormat> outputFormat, String outPath, Configuration conf)
	    throws IOException {

		Job job = buildMapRedCounterJobWithoutCombiner(name, outputFormat, outPath, conf);
		job.setCombinerClass(MapRedCountCombiner.class);

		return job;
	}

	protected static Job buildMapRedCounterJobWithoutCombiner(String name,
	    @SuppressWarnings("rawtypes") Class<? extends OutputFormat> outputFormat, String outPath, Configuration conf)
	    throws IOException {

		Job job = new Job(conf, name);

		Path output = new Path(outPath);
		HadoopUtils.deleteIfExists(FileSystem.get(conf), output);
		job.setJarByClass(MapRedCounter.class);

		job.setReducerClass(MapRedCountReducer.class);
		job.setMapOutputKeyClass(CounterKey.class);
		job.setMapOutputValueClass(CounterValue.class);
		job.setOutputFormatClass(outputFormat);
		job.setOutputKeyClass(NullWritable.class);
		job.setOutputValueClass(NullWritable.class);

		// Secondary sorting configuration.
		job.setGroupingComparatorClass(CounterKey.IdGroupComparator.class);
		job.setPartitionerClass(CounterKey.IdGroupPartitioner.class);

		FileOutputFormat.setOutputPath(job, output);

		// Multioutput configuration
		MultipleOutputs.setCountersEnabled(job, true);
		MultipleOutputs.addNamedOutput(job, Outputs.COUNTFILE.toString(), SequenceFileOutputFormat.class,
		    CounterKey.class, LongWritable.class);
		MultipleOutputs.addNamedOutput(job, Outputs.COUNTDISTINCTFILE.toString(), SequenceFileOutputFormat.class,
		    CounterDistinctKey.class, LongPairWritable.class);

		return job;
	}

	/**
	 * Adds an input file and {@link MapRedCounterMapper} to be processed for emit groups and items that then will be
	 * counted. Remember you have to implement your own {@link MapRedCounterMapper} to be provided here.
	 */
	@SuppressWarnings({ "rawtypes" })
	public static void addInput(Job job, Path location, Class<? extends InputFormat> inputFormat,
	    Class<? extends MapRedCounterMapper> mapper) throws IOException {

		MultipleInputs.addInputPath(job, location, inputFormat, mapper);
		job.setJarByClass(mapper);
	}

}
