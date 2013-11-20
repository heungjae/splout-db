package com.splout.db.engine;

import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.OutputFormat;

import com.datasalt.pangool.io.ITuple;
import com.splout.db.hadoop.TableSpec;
import com.splout.db.hadoop.TablespaceSpec;
import com.splout.db.hadoop.engine.MySQLOutputFormat;
import com.splout.db.hadoop.engine.SQLite4JavaOutputFormat;

/**
 * Stateless factory that should be used to provide an appropriate OutputFormat for generating a Tablespace with a certain
 * {@link Engine}. Will be called by {@link TablespaceGenerator} before executing the generation job.
 * <p>
 * The contract of the OutputFormat is to produce a single "partition_id".db binary file with the contents that the engine
 * needs to act upon. If the engine needs multiple files, this file should be compressed for example with {@link CompressionUtil},
 * and decompressed in the "server" factory.
 */
public class OutputFormatFactory {

	public static OutputFormat<ITuple, NullWritable> getOutputFormat(TablespaceSpec tablespace, int batchSize, TableSpec[] tbls) throws Exception {
		OutputFormat<ITuple, NullWritable> oF = null;
		
		if(tablespace.getEngine().equals(Engine.SQLITE)) {
			oF = new SQLite4JavaOutputFormat(batchSize,	tbls);
		} else if(tablespace.getEngine().equals(Engine.MYSQL)) {
			oF = new MySQLOutputFormat(batchSize, tablespace.getnPartitions(), tbls);
		} else {
			throw new IllegalArgumentException("Engine not supported: " + tablespace.getEngine());
		}
		
		return oF;
	}
}