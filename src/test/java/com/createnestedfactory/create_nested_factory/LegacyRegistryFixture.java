package com.createnestedfactory.create_nested_factory;

/** Only used by headless tests: Forge's wrapper and MappedRegistry keep separate frozen fields. */
final class LegacyRegistryFixture {
    private LegacyRegistryFixture() {}
    static void reopenWrapper(Object registry) throws ReflectiveOperationException {
        for (Class<?> type = registry.getClass(); type != null; type = type.getSuperclass()) {
            for (String fieldName : new String[]{"frozen", "locked"}) {
                try {
                    var field = type.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    field.set(registry, false);
                } catch (NoSuchFieldException ignored) {}
            }
        }
    }
}
