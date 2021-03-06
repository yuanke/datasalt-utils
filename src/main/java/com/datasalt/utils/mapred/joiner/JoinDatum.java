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

package com.datasalt.utils.mapred.joiner;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;


import org.apache.hadoop.io.Writable;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;

import com.datasalt.utils.commons.ThriftUtils;

@SuppressWarnings("rawtypes")
public class JoinDatum<T extends TBase> implements Writable {

	public enum Source {
		OLD, NEW
	}
	
	Source source;
	byte[] datumSer;
	
	// Default constructor needed.
	public JoinDatum() {
		
	}
	
	public JoinDatum(Source source, T datum) throws TException {
		this.source = source;
		setDatum(datum);
	}
	
	private void serialize(T datum) throws TException {
		datumSer = ThriftUtils.getSerializer().serialize(datum);
	}

	/**
	 * Each call to this method, deserialize the datum.
	 * Don't abuse to call it. Cache the response.
	 */
	public T getDatum(T datum) throws TException {
		ThriftUtils.getDeserializer().deserialize(datum, datumSer);
		return datum;
	}
	
	/**
	 * Each call to this method, serialize the datum.
	 * Don't abuse to call it.
	 */
	public void setDatum(@Nullable T datum) throws TException {
		if (datum == null) {
			datumSer = null;
		} else {
			serialize(datum);
		}
	}
	
	public Source getSource() {
		return source;
	}
	
	public void setSource(@Nonnull Source source) {
		this.source = source;
	}
	
	@Override
  public void readFields(DataInput in) throws IOException {
		source = Source.values()[in.readByte()];
		int size = in.readInt();
		if ( size >= 0 ) {
			byte[] bytes = new byte[size]; 
			in.readFully(bytes);
			datumSer = bytes;
		} else {
			datumSer = null;
		}
  }

	@Override
  public void write(DataOutput out) throws IOException {
		out.writeByte(source.ordinal());
		if (datumSer == null) {
			out.writeInt(-1);
		} else {
			out.writeInt(datumSer.length);
			out.write(datumSer);
		}
  }
}
