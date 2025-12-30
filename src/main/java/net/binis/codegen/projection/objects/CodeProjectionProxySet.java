package net.binis.codegen.projection.objects;

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

import net.binis.codegen.factory.CodeFactory;

import java.util.*;

@SuppressWarnings("unchecked")
public class CodeProjectionProxySet implements Set {

    private final Set set;
    private final Class<?>[] proxies;

    public CodeProjectionProxySet(Set set, Class<?>... proxies) {
        this.set = set;
        this.proxies = proxies;
    }

    @Override
    public int size() {
        return set.size();
    }

    @Override
    public boolean isEmpty() {
        return set.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return set.contains(CodeProxyBase.unwrap(o));
    }

    @Override
    public Iterator iterator() {
        return new CodeProjectionProxyIterator(set.iterator(), proxies);
    }

    @Override
    public Object[] toArray() {
        var result = set.toArray();
        for (var i = 0; i < result.length; i++) {
            result[i] = CodeFactory.projection(result[i], proxies[0]);
        }
        return result;
    }

    @Override
    public boolean add(Object o) {
        return set.add(CodeProxyBase.unwrap(o));
    }

    @Override
    public boolean remove(Object o) {
        return set.remove(CodeProxyBase.unwrap(o));
    }

    @Override
    public boolean addAll(Collection c) {
        var result = true;
        for (var o : c) {
            result = result && add(o);
        }
        return result;
    }

    @Override
    public void clear() {
        set.clear();
    }

    @Override
    public boolean removeAll(Collection c) {
        var result = true;
        for (var o : c) {
            result = result && remove(o);
        }
        return result;
    }

    @Override
    public boolean retainAll(Collection c) {
        return false;
    }

    @Override
    public boolean containsAll(Collection c) {
        var result = true;
        for (var o : c) {
            result = result && contains(o);
        }
        return result;
    }

    @Override
    public Object[] toArray(Object[] a) {
        for (var i = 0; i < a.length; i++) {
            a[i] = CodeProxyBase.unwrap(a[i]);
        }
        var result = set.toArray(a);
        for (var i = 0; i < result.length; i++) {
            result[i] = CodeFactory.projection(result[i], proxies[0]);
        }
        return result;    }
}
