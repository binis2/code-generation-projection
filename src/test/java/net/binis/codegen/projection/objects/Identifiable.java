package net.binis.codegen.projection.objects;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.util.UUID;

@FunctionalInterface
public interface Identifiable {

    UUID getId();

}
