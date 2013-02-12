package com.splout.db.hadoop;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class GeneratorCMDIntegrationTest {

	@After
	@Before
	public void cleanUp() throws InterruptedException, IOException {
		Runtime.getRuntime().exec("rm -rf out-cascading-logs").waitFor();
		Runtime.getRuntime().exec("rm -rf out-pig-wordcount").waitFor();
		Runtime.getRuntime().exec("rm -rf out-hashtags").waitFor();
	}
	
	@Test
	public void testCascading() throws Exception {
		List<String> args = new ArrayList<String>();
		args.add("-o");
		args.add("out-cascading-logs");
		args.add("-tf");
		args.add("src/test/resources/cascading-tablespace.json");
		
		GeneratorCMD.main(args.toArray(new String[0]));
		
		assertTrue(new File("out-cascading-logs/cascading_logs/store", "0.db").exists());
		assertTrue(new File("out-cascading-logs/cascading_logs/store", "0.db").length() > 0);
	}
	
	@Test
	public void testTuple() throws Exception {
		List<String> args = new ArrayList<String>();
		args.add("-o");
		args.add("out-pig-wordcount");
		args.add("-tf");
		args.add("src/test/resources/tuple-tablespace.json");
		
		GeneratorCMD.main(args.toArray(new String[0]));
		
		assertTrue(new File("out-pig-wordcount/pig_word_count/store", "0.db").exists());
		assertTrue(new File("out-pig-wordcount/pig_word_count/store", "0.db").length() > 0);
		assertTrue(new File("out-pig-wordcount/pig_word_count/store", "1.db").exists());
		assertTrue(new File("out-pig-wordcount/pig_word_count/store", "1.db").length() > 0);
	}
	
	@Test
	public void testText() throws Exception {
		List<String> args = new ArrayList<String>();
		args.add("-o");
		args.add("out-hashtags");
		args.add("-tf");
		args.add("src/test/resources/text-tablespace.json");
		
		GeneratorCMD.main(args.toArray(new String[0]));
		
		assertTrue(new File("out-hashtags/hashtags/store", "0.db").exists());
		assertTrue(new File("out-hashtags/hashtags/store", "0.db").length() > 0);
		assertTrue(new File("out-hashtags/hashtags/store", "1.db").exists());
		assertTrue(new File("out-hashtags/hashtags/store", "1.db").length() > 0);
	}
}
