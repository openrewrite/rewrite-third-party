/*
 * Copyright 2026 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.aws.sdk;

/**
 * Source of {@code AwsSyncClients}, which {@link PrepareAwsAsyncClientCalls} generates where a v1 async client is used as a sync
 * client. A v1 async client implemented the sync interface; v2's does not, so a blocking view of the same client takes its place.
 */
final class AwsSyncClientsShim {

    static final String SHIM = "AwsSyncClients";

    private AwsSyncClientsShim() {
    }

    static String source(String pkg) {
        return ProjectShims.packageLine(pkg) +
               "import java.lang.reflect.InvocationTargetException;\n" +
               "import java.lang.reflect.Method;\n" +
               "import java.lang.reflect.Proxy;\n" +
               "import java.util.ArrayDeque;\n" +
               "import java.util.Deque;\n" +
               "import java.util.concurrent.CompletableFuture;\n" +
               "import java.util.concurrent.CompletionException;\n" +
               "\n" +
               "/**\n" +
               " * AWS SDK v1 async clients were also sync clients. In SDK v2 they are not, so code that handed an async client to\n" +
               " * something expecting a sync client gets a blocking view of the same async client instead: each call waits for its\n" +
               " * result and throws the SDK exception itself, as the v1 call did.\n" +
               " */\n" +
               "public final class " + SHIM + " {\n" +
               "\n" +
               "    private " + SHIM + "() {\n" +
               "    }\n" +
               "\n" +
               "    public static <T> T blocking(Object asyncClient, Class<T> syncInterface) {\n" +
               "        if (asyncClient == null || syncInterface.isInstance(asyncClient)) {\n" +
               "            return syncInterface.cast(asyncClient);\n" +
               "        }\n" +
               "        return syncInterface.cast(Proxy.newProxyInstance(syncInterface.getClassLoader(), new Class<?>[]{syncInterface},\n" +
               "                (proxy, method, args) -> {\n" +
               "                    if (method.getDeclaringClass() == Object.class) {\n" +
               "                        switch (method.getName()) {\n" +
               "                            case \"equals\":\n" +
               "                                return proxy == args[0];\n" +
               "                            case \"hashCode\":\n" +
               "                                return System.identityHashCode(proxy);\n" +
               "                            default:\n" +
               "                                return \"blocking \" + asyncClient;\n" +
               "                        }\n" +
               "                    }\n" +
               "                    Method target = asyncMethod(asyncClient.getClass(), method);\n" +
               "                    if (target == null) {\n" +
               "                        throw new UnsupportedOperationException(method.getName() + \" has no async equivalent on \" +\n" +
               "                                asyncClient.getClass().getName());\n" +
               "                    }\n" +
               "                    Object result;\n" +
               "                    try {\n" +
               "                        result = target.invoke(asyncClient, args);\n" +
               "                    } catch (InvocationTargetException e) {\n" +
               "                        throw e.getCause();\n" +
               "                    }\n" +
               "                    if (result instanceof CompletableFuture && !method.getReturnType().isInstance(result)) {\n" +
               "                        try {\n" +
               "                            return ((CompletableFuture<?>) result).join();\n" +
               "                        } catch (CompletionException e) {\n" +
               "                            throw e.getCause() == null ? e : e.getCause();\n" +
               "                        }\n" +
               "                    }\n" +
               "                    if (result != null && !method.getReturnType().isPrimitive() && !method.getReturnType().isInstance(result)) {\n" +
               "                        // Paginators and waiters return different types on the async client\n" +
               "                        throw new UnsupportedOperationException(method.getName() + \" has no blocking equivalent on \" +\n" +
               "                                asyncClient.getClass().getName());\n" +
               "                    }\n" +
               "                    return result;\n" +
               "                }));\n" +
               "    }\n" +
               "\n" +
               "    /**\n" +
               "     * Looked up on the client's public interfaces, since SDK client implementations are not public classes.\n" +
               "     */\n" +
               "    private static Method asyncMethod(Class<?> type, Method syncMethod) {\n" +
               "        Deque<Class<?>> queue = new ArrayDeque<>();\n" +
               "        for (Class<?> c = type; c != null; c = c.getSuperclass()) {\n" +
               "            for (Class<?> i : c.getInterfaces()) {\n" +
               "                queue.add(i);\n" +
               "            }\n" +
               "        }\n" +
               "        while (!queue.isEmpty()) {\n" +
               "            Class<?> i = queue.poll();\n" +
               "            try {\n" +
               "                return i.getMethod(syncMethod.getName(), syncMethod.getParameterTypes());\n" +
               "            } catch (NoSuchMethodException e) {\n" +
               "                for (Class<?> parent : i.getInterfaces()) {\n" +
               "                    queue.add(parent);\n" +
               "                }\n" +
               "            }\n" +
               "        }\n" +
               "        return null;\n" +
               "    }\n" +
               "}\n";
    }
}
