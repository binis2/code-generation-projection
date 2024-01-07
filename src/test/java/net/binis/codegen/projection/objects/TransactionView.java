package net.binis.codegen.projection.objects;

import java.util.UUID;

public interface TransactionView extends Identifiable {

    MerchantView getMerchant();

    UUID getMerchantId();

}
