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

import lombok.Getter;
import lombok.Setter;
import net.binis.codegen.projection.objects.Identifiable;
import net.binis.codegen.projection.objects.TransactionView;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ComplexProjectionsTest {

    @Test
    void test() {
        var obj = new Obj();
        var merch = new Sub();
        obj.setMerchant(merch);
        merch.setId(UUID.randomUUID());

        var view = Projection.single(obj, TransactionView.class);

        assertNotNull(view.getMerchant());
        assertNotNull(view.getMerchantId());
    }



    @Getter
    @Setter
    public static class Base implements Identifiable {
        UUID id;
    }

    @Getter
    @Setter
    public static class Sub extends Base implements Merchant {
        String name;
    }

    @Getter
    @Setter
    public static class Obj {
        Merchant merchant;

        public UUID getMerchantId2() {
            return getMerchant().getId();
        }
    }

    public interface Merchant extends Identifiable {
    }

}
