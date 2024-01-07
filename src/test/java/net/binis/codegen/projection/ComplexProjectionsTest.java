package net.binis.codegen.projection;

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
