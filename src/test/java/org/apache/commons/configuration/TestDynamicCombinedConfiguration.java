/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.configuration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Random;

import org.apache.commons.configuration.builder.BuilderConfigurationWrapperFactory;
import org.apache.commons.configuration.builder.ConfigurationBuilder;
import org.apache.commons.configuration.builder.FileBasedBuilderParametersImpl;
import org.apache.commons.configuration.builder.combined.CombinedConfigurationBuilder;
import org.apache.commons.configuration.builder.combined.MultiFileConfigurationBuilder;
import org.apache.commons.configuration.builder.combined.ReloadingCombinedConfigurationBuilder;
import org.apache.commons.configuration.builder.fluent.Parameters;
import org.apache.commons.configuration.interpol.ConfigurationInterpolator;
import org.apache.commons.configuration.interpol.Lookup;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.junit.Test;

public class TestDynamicCombinedConfiguration
{
    private static String PATTERN = "${sys:Id}";
    private static String PATTERN1 = "target/test-classes/testMultiConfiguration_${sys:Id}.xml";
    private static String DEFAULT_FILE = "target/test-classes/testMultiConfiguration_default.xml";
    private static final File MULTI_TENENT_FILE = new File(
            "conf/testMultiTenentConfigurationBuilder4.xml");
    private static final File MULTI_DYNAMIC_FILE = new File(
            "conf/testMultiTenentConfigurationBuilder5.xml");

    /** Constant for the number of test threads. */
    private static final int THREAD_COUNT = 3;

    /** Constant for the number of loops in the multi-thread tests. */
    private static final int LOOP_COUNT = 100;

    @Test
    public void testConfiguration() throws Exception
    {
        DynamicCombinedConfiguration config = new DynamicCombinedConfiguration();
        XPathExpressionEngine engine = new XPathExpressionEngine();
        config.setExpressionEngine(engine);
        config.setKeyPattern(PATTERN);
        config.setDelimiterParsingDisabled(true);
        ConfigurationBuilder<XMLConfiguration> multiBuilder =
                new MultiFileConfigurationBuilder<XMLConfiguration>(
                        XMLConfiguration.class).configure(Parameters
                        .multiFile()
                        .setFilePattern(PATTERN1)
                        .setPrefixLookups(
                                ConfigurationInterpolator
                                        .getDefaultPrefixLookups())
                        .setManagedBuilderParameters(
                                Parameters.xml().setExpressionEngine(engine)));
        BuilderConfigurationWrapperFactory wrapFactory =
                new BuilderConfigurationWrapperFactory();
        config.addConfiguration(wrapFactory.createBuilderConfigurationWrapper(
                HierarchicalConfiguration.class, multiBuilder), "Multi");
        XMLConfiguration xml = new XMLConfiguration();
        xml.setExpressionEngine(engine);
        xml.setDelimiterParsingDisabled(true);
        xml.setFile(new File(DEFAULT_FILE));
        xml.load();
        config.addConfiguration(xml, "Default");

        verify("1001", config, 15);
        verify("1002", config, 25);
        verify("1003", config, 35);
        verify("1004", config, 50);
        assertEquals("a,b,c", config.getString("split/list3/@values"));
        assertEquals(0, config.getMaxIndex("split/list3/@values"));
        assertEquals("a\\,b\\,c", config.getString("split/list4/@values"));
        assertEquals("a,b,c", config.getString("split/list1"));
        assertEquals(0, config.getMaxIndex("split/list1"));
        assertEquals("a\\,b\\,c", config.getString("split/list2"));
    }

    @Test
    public void testConcurrentGetAndReload() throws Exception
    {
        System.getProperties().remove("Id");
        CombinedConfigurationBuilder builder = new CombinedConfigurationBuilder();
        builder.configure(Parameters.fileBased().setFile(MULTI_TENENT_FILE));
        CombinedConfiguration config = builder.getConfiguration();

        assertEquals("Wrong value", "50", config.getString("rowsPerPage"));
        Thread testThreads[] = new Thread[THREAD_COUNT];
        int failures[] = new int[THREAD_COUNT];

        for (int i = 0; i < testThreads.length; ++i)
        {
            testThreads[i] = new ReloadThread(builder, failures, i, LOOP_COUNT, false, null, "50");
            testThreads[i].start();
        }

        int totalFailures = 0;
        for (int i = 0; i < testThreads.length; ++i)
        {
            testThreads[i].join();
            totalFailures += failures[i];
        }
        assertEquals(totalFailures + " failures Occurred", 0, totalFailures);
    }

    @Test
    public void testConcurrentGetAndReload2() throws Exception
    {
        System.getProperties().remove("Id");
        CombinedConfigurationBuilder builder = new CombinedConfigurationBuilder();
        builder.configure(Parameters.fileBased().setFile(MULTI_TENENT_FILE));
        CombinedConfiguration config = builder.getConfiguration();

        assertEquals(config.getString("rowsPerPage"), "50");

        Thread testThreads[] = new Thread[THREAD_COUNT];
        int failures[] = new int[THREAD_COUNT];
        System.setProperty("Id", "2002");
        assertEquals("Wrong value", "25", config.getString("rowsPerPage"));
        for (int i = 0; i < testThreads.length; ++i)
        {
            testThreads[i] = new ReloadThread(builder, failures, i, LOOP_COUNT, false, null, "25");
            testThreads[i].start();
        }

        int totalFailures = 0;
        for (int i = 0; i < testThreads.length; ++i)
        {
            testThreads[i].join();
            totalFailures += failures[i];
        }
        System.getProperties().remove("Id");
        assertEquals(totalFailures + " failures Occurred", 0, totalFailures);
    }

    @Test
    public void testConcurrentGetAndReloadMultipleClients() throws Exception
    {
        System.getProperties().remove("Id");
        CombinedConfigurationBuilder builder = new CombinedConfigurationBuilder();
        builder.configure(Parameters.fileBased().setFile(MULTI_TENENT_FILE));
        CombinedConfiguration config = builder.getConfiguration();

        assertEquals(config.getString("rowsPerPage"), "50");

        Thread testThreads[] = new Thread[THREAD_COUNT];
        int failures[] = new int[THREAD_COUNT];
        String[] ids = new String[] {null, "2002", "3001", "3002", "3003"};
        String[] expected = new String[] {"50", "25", "15", "25", "50"};
        for (int i = 0; i < testThreads.length; ++i)
        {
            testThreads[i] = new ReloadThread(builder, failures, i, LOOP_COUNT, true, ids[i], expected[i]);
            testThreads[i].start();
        }

        int totalFailures = 0;
        for (int i = 0; i < testThreads.length; ++i)
        {
            testThreads[i].join();
            totalFailures += failures[i];
        }
        System.getProperties().remove("Id");
        if (totalFailures != 0)
        {
            System.out.println("Failures:");
            for (int i = 0; i < testThreads.length; ++i)
            {
                System.out.println("Thread " + i + " " + failures[i]);
            }
        }
        assertEquals(totalFailures + " failures Occurred", 0, totalFailures);
    }

    @Test
    public void testConcurrentGetAndReloadFile() throws Exception
    {
        final int threadCount = 25;
        System.getProperties().remove("Id");
        // create a new configuration
        File input = new File("target/test-classes/testMultiDynamic_default.xml");
        File output = new File("target/test-classes/testwrite/testMultiDynamic_default.xml");
        output.delete();
        output.getParentFile().mkdir();
        copyFile(input, output);

        ReloadingCombinedConfigurationBuilder builder = new ReloadingCombinedConfigurationBuilder();
        builder.configure(new FileBasedBuilderParametersImpl().setFile(MULTI_DYNAMIC_FILE));
        CombinedConfiguration config = builder.getConfiguration();
        assertEquals("Wrong property value (1)", "ID0001",
                config.getString("Product/FIIndex/FI[@id='123456781']"));

        ReaderThread testThreads[] = new ReaderThread[threadCount];
        for (int i = 0; i < testThreads.length; ++i)
        {
            testThreads[i] = new ReaderThread(builder);
            testThreads[i].start();
        }

        builder.getReloadingController().checkForReloading(null);
        Thread.sleep(2000);

        input = new File("target/test-classes/testMultiDynamic_default2.xml");
        copyFile(input, output);

        Thread.sleep(2000);
        assertTrue("Changed file not detected", builder
                .getReloadingController().checkForReloading(null));
        config = builder.getConfiguration();
        String id = config.getString("Product/FIIndex/FI[@id='123456782']");
        assertNotNull("File did not reload, id is null", id);
        String rows = config.getString("rowsPerPage");
        assertEquals("Incorrect value for rowsPerPage", "25", rows);

        for (int i = 0; i < testThreads.length; ++i)
        {
            testThreads[i].shutdown();
            testThreads[i].join();
        }
        for (int i = 0; i < testThreads.length; ++i)
        {
            assertFalse(testThreads[i].failed());
        }
        assertEquals("ID0002", config.getString("Product/FIIndex/FI[@id='123456782']"));
        output.delete();
    }


    private class ReloadThread extends Thread
    {
        private final CombinedConfigurationBuilder builder;
        private final int[] failures;
        private final int index;
        private final int count;
        private final String expected;
        private final String id;
        private final boolean useId;
        private final Random random;

        ReloadThread(CombinedConfigurationBuilder b, int[] failures, int index, int count,
                     boolean useId, String id, String expected)
        {
            builder = b;
            this.failures = failures;
            this.index = index;
            this.count = count;
            this.expected = expected;
            this.id = id;
            this.useId = useId;
            random = new Random();
        }

        @Override
        public void run()
        {
            failures[index] = 0;

            if (useId)
            {
                ThreadLookup.setId(id);
            }
            for (int i = 0; i < count; i++)
            {
                try
                {
                    if(random.nextBoolean())
                    {
                        // simulate a reload
                        builder.resetResult();
                    }
                    CombinedConfiguration combined = builder.getConfiguration();
                    String value = combined.getString("rowsPerPage", null);
                    if (value == null || !value.equals(expected))
                    {
                        ++failures[index];
                    }
                }
                catch (Exception ex)
                {
                    ++failures[index];
                }
            }
        }
    }

    private class ReaderThread extends Thread
    {
        private volatile boolean running = true;
        private volatile boolean failed = false;
        private final CombinedConfigurationBuilder builder;

        public ReaderThread(CombinedConfigurationBuilder b)
        {
            builder = b;
        }

        @Override
        public void run()
        {
            try
            {
                while (running)
                {
                    CombinedConfiguration combined = builder.getConfiguration();
                    String bcId =
                            combined.getString("Product/FIIndex/FI[@id='123456781']");
                    if ("ID0001".equalsIgnoreCase(bcId))
                    {
                        if (failed)
                        {
                            System.out.println("Thread failed, but recovered");
                        }
                        failed = false;
                    }
                    else
                    {
                        failed = true;
                    }
                }
            }
            catch (ConfigurationException cex)
            {
                failed = true;
            }
        }

        public boolean failed()
        {
            return failed;
        }

        public void shutdown()
        {
            running = false;
        }

    }

    private void verify(String key, DynamicCombinedConfiguration config, int rows)
    {
        System.setProperty("Id", key);
        assertTrue(config.getInt("rowsPerPage") == rows);
    }

    private void copyFile(File input, File output) throws IOException
    {
        Reader reader = new FileReader(input);
        Writer writer = new FileWriter(output);
        char[] buffer = new char[4096];
        int n = 0;
        while (-1 != (n = reader.read(buffer)))
        {
            writer.write(buffer, 0, n);
        }
        reader.close();
        writer.close();
    }

    public static class ThreadLookup implements Lookup
    {
        private static ThreadLocal<String> id = new ThreadLocal<String>();

        public ThreadLookup()
        {
        }

        public static void setId(String value)
        {
            id.set(value);
        }

        public String lookup(String key)
        {
            if (key == null || !key.equals("Id"))
            {
                return null;
            }
            String value = System.getProperty("Id");
            if (value != null)
            {
                return value;
            }
            return id.get();

        }
    }
}
