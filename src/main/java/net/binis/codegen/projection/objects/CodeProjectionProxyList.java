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

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

@SuppressWarnings("unchecked")
public class CodeProjectionProxyList implements List {

    private final List list;
    private final Class<?>[] proxies;

    public CodeProjectionProxyList(List list, Class<?>... proxies) {
        this.list = list;
        this.proxies = proxies;
    }

    @Override
    public int size() {
        return list.size();
    }

    @Override
    public boolean isEmpty() {
        return list.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return list.contains(CodeProxyBase.unwrap(o));
    }

    @Override
    public Iterator iterator() {
        return new CodeProjectionProxyIterator(list.iterator(), proxies);
    }

    @Override
    public Object[] toArray() {
        var result = list.toArray();
        for (var i = 0; i < result.length; i++) {
            result[i] = CodeFactory.projection(result[i], proxies[0]);
        }
        return result;
    }

    @Override
    public boolean add(Object o) {
        return list.add(CodeProxyBase.unwrap(o));
    }

    @Override
    public boolean remove(Object o) {
        return list.remove(CodeProxyBase.unwrap(o));
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
    public boolean addAll(int index, Collection c) {
        return false;
    }

    @Override
    public void clear() {
        list.clear();
    }

    @Override
    public Object get(int index) {
        return CodeFactory.projection(list.get(index), proxies[0]);
    }

    @Override
    public Object set(int index, Object element) {
        return list.set(index, CodeProxyBase.unwrap(element));
    }

    @Override
    public void add(int index, Object element) {
        list.add(index, CodeProxyBase.unwrap(element));
    }

    @Override
    public Object remove(int index) {
        return CodeFactory.projection(list.remove(index), proxies[0]);
    }

    @Override
    public int indexOf(Object o) {
        return list.indexOf(CodeProxyBase.unwrap(o));
    }

    @Override
    public int lastIndexOf(Object o) {
        return list.lastIndexOf(CodeProxyBase.unwrap(o));
    }

    @Override
    public ListIterator listIterator() {
        return new CodeProjectionProxyListIterator(list.listIterator(), proxies);
    }

    @Override
    public ListIterator listIterator(int index) {
        return new CodeProjectionProxyListIterator(list.listIterator(index), proxies);
    }

    @Override
    public List subList(int fromIndex, int toIndex) {
        return new CodeProjectionProxyList(list.subList(fromIndex, toIndex), proxies);
    }

    @Override
    public boolean retainAll(Collection c) {
        return false;
    }

    @Override
    public boolean removeAll(Collection c) {
        return false;
    }

    @Override
    public boolean containsAll(Collection c) {
        return false;
    }

    @Override
    public Object[] toArray(Object[] a) {
        for (var i = 0; i < a.length; i++) {
            a[i] = CodeProxyBase.unwrap(a[i]);
        }
        var result = list.toArray(a);
        for (var i = 0; i < result.length; i++) {
            result[i] = CodeFactory.projection(result[i], proxies[0]);
        }
        return result;
    }
}
