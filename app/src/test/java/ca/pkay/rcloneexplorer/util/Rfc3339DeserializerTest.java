package ca.pkay.rcloneexplorer.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;

public class Rfc3339DeserializerTest {

    static class TestContainer {
        @JsonDeserialize(using = Rfc3339Deserializer.class)
        public Long time;
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testValidDate() throws IOException {
        String json = "{\"time\": \"2023-10-26T12:00:00Z\"}";
        TestContainer container = mapper.readValue(json, TestContainer.class);

        assertNotNull(container.time);
        // 2023-10-26T12:00:00Z is 1698321600000
        assertEquals(1698321600000L, (long)container.time);
    }

    @Test
    public void testValidDateWithTimezone() throws IOException {
        String json = "{\"time\": \"2023-10-26T12:00:00+02:00\"}";
        // 12:00+02:00 is 10:00 UTC.
        // 2023-10-26T10:00:00Z is 1698314400000
        TestContainer container = mapper.readValue(json, TestContainer.class);

        assertNotNull(container.time);
        assertEquals(1698314400000L, (long)container.time);
    }

    @Test
    public void testInvalidDate() throws IOException {
        String json = "{\"time\": \"invalid-date-string\"}";
        TestContainer container = mapper.readValue(json, TestContainer.class);

        assertNotNull(container.time);
        assertEquals(0L, (long)container.time);
    }

    @Test
    public void testEmptyString() throws IOException {
        String json = "{\"time\": \"\"}";
        TestContainer container = mapper.readValue(json, TestContainer.class);

        assertNotNull(container.time);
        assertEquals(0L, (long)container.time);
    }

    @Test
    public void testNullString() throws IOException {
        String json = "{\"time\": null}";
        TestContainer container = mapper.readValue(json, TestContainer.class);

        // For null value in JSON, Jackson sets the field to null (if it's an object type like Long)
        assertNull(container.time);
    }

    @Test
    public void testNumericInput() throws IOException {
        // Numeric input as string
        String json = "{\"time\": \"123456\"}";
        TestContainer container = mapper.readValue(json, TestContainer.class);
        // Should fail parsing as RFC3339 string and return 0L
        assertEquals(0L, (long)container.time);
    }

    @Test
    public void testNumericInputDirect() throws IOException {
        // Numeric input as number
        String json = "{\"time\": 123456}";
        TestContainer container = mapper.readValue(json, TestContainer.class);
        // parser.getText() on number node returns string representation "123456"
        // parseCalendar("123456") throws ParseException -> returns 0L
        assertEquals(0L, (long)container.time);
    }
}
