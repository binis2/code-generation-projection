package net.binis.codegen.projection;

/*-
 * #%L
 * code-generator-projection
 * %%
 * Copyright (C) 2021 - 2024 Binis Belev
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */


import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.binis.codegen.factory.CodeFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
class ProxiesTest {

    private InvocationHandler handler = (proxy, method, args) -> {
        if (method.getName().equals("getName")) {
            return "Test";
        } else if (method.getName().equals("getAge")) {
            return 10;
        } else if (method.getName().equals("getList")) {
            return List.of("1", "2", "3");
        } else if (method.getName().equals("getSet")) {
            return Set.of("1", "2", "3");
        } else if (method.getName().equals("getMap")) {
            return Map.of("1", "2", "3", "4");
        }
        return null;
    };


    @Test
    void testClass() {
        var proxy = CodeFactory.proxy(TestClass.class, handler);

        assertEquals("Test", proxy.getName());
        assertEquals(10, proxy.getAge());
        assertEquals(List.of("1", "2", "3"), proxy.getList());
        assertEquals(Set.of("1", "2", "3"), proxy.getSet());
        assertEquals(Map.of("1", "2", "3", "4"), proxy.getMap());
    }

    @Test
    void testInterface() {
        var proxy = CodeFactory.proxy(TestIntf.class, handler);

        assertEquals("Test", proxy.getName());
        assertEquals(10, proxy.getAge());
        assertEquals(List.of("1", "2", "3"), proxy.getList());
        assertEquals(Set.of("1", "2", "3"), proxy.getSet());
        assertEquals(Map.of("1", "2", "3", "4"), proxy.getMap());
    }


    @Data
    @NoArgsConstructor
    public static class TestClass {
        public String name;
        public int age;
        public List<String> list;
        public Set<String> set;
        public Map<String, String> map;
    }

    public interface TestIntf {
        String getName();

        int getAge();

        List<String> getList();

        Set<String> getSet();

        Map<String, String> getMap();
    }

}
