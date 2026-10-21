package dev.poco.asistente.core;

import java.util.function.BooleanSupplier;

/** Text-only port. Its output never enters the tool parser/executor. */
public interface Pro {
    String generate(String type, String prompt, String model, BooleanSupplier cancelled) throws Exception;
    Pro UNAVAILABLE = (type, prompt, model, cancelled) -> { throw new IllegalArgumentException("Pro no disponible ahora."); };
}
