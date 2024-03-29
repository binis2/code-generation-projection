package net.binis.codegen.projection.objects;

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

import net.binis.codegen.factory.CodeFactory;

import java.util.Iterator;

public class CodeProjectionProxyIterator implements Iterator {

    private final Iterator iterator;
    private final Class<?>[] proxies;

    public CodeProjectionProxyIterator(Iterator iterator, Class<?>... proxies) {
        this.iterator = iterator;
        this.proxies = proxies;
    }

    @Override
    public boolean hasNext() {
        return iterator.hasNext();
    }

    @Override
    public Object next() {
        return CodeFactory.projection(iterator.next(), proxies[0]);
    }

}
