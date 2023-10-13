package org.apache.solr.crossdc.common;

import org.apache.lucene.util.LuceneTestCase;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ConfigUtilTest extends LuceneTestCase {
    @Test
    public void testPropertyFromFile() throws Exception {
        // Create a temporary file
        Path tmpFile = Files.createTempFile("testPropertyFromFile", ".txt");

        // Write a value to the file
        String fileValue = "testValue";
        Files.write(tmpFile, fileValue.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> properties = new HashMap<>();

        // Set a property to @file:{tmpFilePath}
        String propertyKey = KafkaCrossDcConf.TOPIC_NAME;
        properties.put(propertyKey, "@file:" + tmpFile.toString());

        // Call the method that processes the properties
        ConfUtil.processFileValueReferences(properties);

        // Verify that the property value has been correctly read from the file
        assertEquals(fileValue, properties.get(propertyKey));

        // Clean up the temporary file
        Files.delete(tmpFile);
    }
}