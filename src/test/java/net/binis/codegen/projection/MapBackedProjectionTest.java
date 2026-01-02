package net.binis.codegen.projection;

/*-
 * #%L
 * code-generator-projection
 * %%
 * Copyright (C) 2021 - 2026 Binis Belev
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


import lombok.extern.slf4j.Slf4j;
import net.binis.codegen.factory.CodeFactory;
import net.binis.codegen.projection.provider.MapBackedProjectionProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
class MapBackedProjectionTest {

    private static final Map<String, Object> map = Map.of(
            "int", "5",
            "string", 6.0,
            "double", "7",
            "long", "8"
    );

    public interface Projection {
        int getInt();
        String getString();
        double getDouble();
        long getLong();
        float getFloat();
    }

    @Test
    void test() {
        var p = MapBackedProjectionProvider.create(map, Projection.class);

        assertEquals(5, p.getInt());
        assertEquals("6.0", p.getString());
        assertEquals(7.0, p.getDouble());
        assertEquals(8L, p.getLong());
        assertEquals(0.0, p.getFloat());
    }

    @Test
    void testFactory() {
        var p = CodeFactory.projection(map, Projection.class);

        assertEquals(5, p.getInt());
        assertEquals("6.0", p.getString());
        assertEquals(7.0, p.getDouble());
        assertEquals(8L, p.getLong());
        assertEquals(0.0, p.getFloat());
    }


}
