package net.binis.codegen.projection;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.binis.codegen.factory.CodeFactory;
import net.binis.codegen.projection.objects.CodeProxyBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class ProjectionsTest {

    ObjectMapper mapper = new ObjectMapper();

    @Test
    void test() throws JsonProcessingException {
        var obj = new TestObject();
        var proxy = CodeFactory.projection(obj, TestProjection.class);

        assertEquals("value", proxy.getValue());
        proxy.setValue("value1");
        assertEquals("value1", proxy.getValue());
        assertEquals("1234", proxy.dummy("1", "2", "3", "4"));
        proxy.dummy("1", "2", "3", "4", "5");
        proxy.nonPresent(null, null);
        assertEquals("sub", proxy.getSubProjection().getSub());
        assertEquals("sub", proxy.getSubProjection2(1, 1).getSub());
        assertEquals("sub", proxy.getSubProjection3(true, 1, '1').getSub());

        assertEquals("sub", proxy.getSubProjectionParentSubProjection2(2, 2).getSub());

        var json = mapper.writeValueAsString(proxy);
        assertEquals(381, json.length());

        var map = mapper.readValue(json, Map.class);
        assertEquals("value1", map.get("value"));
        assertTrue((boolean) map.get("boolean"));
        assertEquals(1, (int) map.get("long"));
        assertEquals(2.0, (double) map.get("float"));
        assertEquals(1.0, (double) map.get("double"));

        assertFalse((boolean) map.get("nonPresentBoolean"));
        assertEquals(0, (int) map.get("nonPresentLong"));
        assertEquals(0.0, (double) map.get("nonPresentFloat"));
        assertEquals(0.0, (double) map.get("nonPresentDouble"));
        assertNull(map.get("nonPresentString"));

        assertNotNull(map.get("subProjection"));
        assertEquals("sub", ((Map) map.get("subProjection")).get("sub"));

        //Sub properties

        assertEquals("works", map.get("subProjectionValue"));
        assertEquals(2.0, map.get("subProjectionDoubleValue"));
        assertEquals(2, map.get("subProjectionLongValue"));
        assertEquals(true, map.get("subProjectionBooleanValue"));
        assertEquals(2.0, map.get("subProjectionParentSubProjectionParentFloat"));
    }

    public interface LongInterface {
        long getLong();
    }

    public interface TestProjection extends LongInterface {
        String getValue();
        void setValue(String value);

        long getLong();
        boolean getBoolean();
        double getDouble();
        float getFloat();

        String dummy(String value, String value1, String value2, String value3);
        void dummy(String value, String value1, String value2, String value3, String value4);

        //Not present
        String getNonPresentString();
        long getNonPresentLong();
        boolean getNonPresentBoolean();
        double getNonPresentDouble();
        float getNonPresentFloat();
        void nonPresent(String param, String param2);

        //SubProjections
        SubProjection getSubProjection();
        SubProjection getSubProjection2(double param1, long param2);
        SubProjection getSubProjection3(boolean param1, float param2, char param3);

        //Sub properties
        String getSubProjectionValue();
        double getSubProjectionDoubleValue();
        long getSubProjectionLongValue();
        boolean getSubProjectionBooleanValue();
        float getSubProjectionParentSubProjectionParentFloat();

        SubProjection getSubProjectionParentSubProjection2(double param1, long param2);
    }

    public interface SubProjection {
        String getSub();
    }

    public static class BaseTestObject {
        public long getLong() {
            return 1;
        }
    }

    @Slf4j
    public static class TestObject extends BaseTestObject {

        private String value = "value";
        public String getValue() {
            return value;
        }

        public boolean getBoolean() {
            return true;
        }
        public double getDouble() {
            return 1.0;
        }
        public float getFloat() {
            return 2.0f;
        }


        public void setValue(String value) {
            this.value = value;
        }

        public String dummy(String value, String value1, String value2, String value3) {
            return value + value1 + value2 + value3;
        }

        public void dummy(String value, String value1, String value2, String value3, String value4) {
            log.info(value + value1 + value2 + value3 + value4);
        }

        public SubObject getSubProjection() {
            return new SubObject(this);
        }

        public SubObject getSubProjection2(double param1, long param2) {
            log.info("getSubProjection2({}, {})", param1, param2);
            return new SubObject(this);
        }

        public SubObject getSubProjection3(boolean param1, float param2, char param3) {
            log.info("getSubProjection3({}, {}, {})", param1, param2, param3);
            return new SubObject(this);
        }

    }

    public static class SubObject {

        private final TestObject parent;

        public SubObject(TestObject parent) {
            this.parent = parent;
        }

        public String getSub() {
            return "sub";
        }

        public double getDoubleValue() {
            return 2.0;
        }
        public long getLongValue() {
            return 2L;
        }
        public boolean getBooleanValue() {
            return true;
        }
        public String getValue() {
            return "works";
        }

        public TestObject getParent() {
            return parent;
        }

    }


    public static class Test2Object extends CodeProxyBase<TestObject> {

        public Test2Object(TestObject value) {
            this.value = value;
        }

        public String getValue() {
            return value.getValue();
        }

        public void setValue(String value) {
            this.value.setValue(value);
        }

        public String dummy(String value, String value1, String value2, String value3) {
            return this.value.dummy(value, value1, value2, value3);
        }

        public void dummy(String value, String value1, String value2, String value3, String value4) {
            this.value.dummy(value, value1, value2, value3, value4);
        }

        public long getLong() {
            return this.value.getLong();
        }

        public void asd() {
            return;
        }

        public void asd(String param) {
            return;
        }

        public String asd2(boolean param, long param2) {
            return null;
        }

        long getNonPresentLong() {
            return 0;
        }

        short getNonPresentShort() {
            return 0;
        }

        byte getNonPresentByte() {
            return 0;
        }

        int getNonPresentInt() {
            return 0;
        }

        boolean getNonPresentBoolean() {
            return false;
        }

        double getNonPresentDouble() {
            return 0;
        }

        SubProjection getSubProjection() {
            return CodeFactory.projection(value.getSubProjection(), SubProjection.class);
        }

        SubProjection getSubProjection2(double param1, long param2) {
            return CodeFactory.projection(value.getSubProjection2(param1, param2), SubProjection.class);
        }

        SubProjection getSubProjection3(boolean param1, float param2, char param3) {
            return CodeFactory.projection(value.getSubProjection3(param1, param2, param3), SubProjection.class);
        }

        String getSubProjectionValue() {
            var p = value.getSubProjection();
            if (p != null) {
                return p.getValue();
            }
            return null;
        }

        long getSubProjectionParentSubProjectionParentLong() {
            var p = value.getSubProjection();
            if (p != null) {
                System.out.println(p);
                var q = p.getParent();
                if (q != null) {
                    System.out.println(p);
                    var r = q.getSubProjection();
                    if (r != null) {
                        System.out.println(p);
                        var s = r.getParent();
                        if (s != null) {
                            System.out.println(p);
                            return s.getLong();
                        }
                    }
                }
            }
            return 0L;
        }

        SubProjection getSubProjectionParentSubProjection2(double param1, long param2) {
            var p = value.getSubProjection();
            if (p != null) {
                var q = p.getParent();
                if (q != null) {
                    return CodeFactory.projection(q.getSubProjection2(param1, param2), SubProjection.class);
                }
            }
            return null;
        }

    }


}
