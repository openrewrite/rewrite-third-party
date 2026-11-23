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
package org.openrewrite.mongodb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Tree;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.style.IntelliJ;
import org.openrewrite.style.GeneralFormatStyle;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.mongodb.table.MongoDriverMigrationDecisions;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.List;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.*;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.test.SourceSpecs.text;

class MigrateMongoJavaDriver3To5Test implements RewriteTest {

    /**
     * Imports stay one per type, so that the expected sources do not depend on when an import layout folds them.
     */
    static final List<NamedStyles> EXPLICIT_IMPORTS = singletonList(new NamedStyles(Tree.randomId(), "explicit-imports",
      "Explicit imports", null, emptySet(), singletonList(IntelliJ.importLayout()
        .withClassCountToUseStarImport(999).withNameCountToUseStarImport(999))));

    static JavaParser.Builder<?, ?> parser(String... artifacts) {
        return JavaParser.fromJavaVersion().styles(EXPLICIT_IMPORTS)
          .classpathFromResources(new InMemoryExecutionContext(), artifacts);
    }

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateMongoJavaDriver3To5()).parser(parser("mongo-java-driver"));
    }

    static final String POM = """
      <project>
          <groupId>com.example</groupId>
          <artifactId>app</artifactId>
          <version>1.0</version>
          <dependencies>
              <dependency>
                  <groupId>org.mongodb</groupId>
                  <artifactId>mongo-java-driver</artifactId>
                  <version>3.12.14</version>
              </dependency>
          </dependencies>
      </project>
      """;

    static org.openrewrite.test.SourceSpecs pom(String artifact) {
        return pomXml(POM, spec -> spec.after(actual -> assertThat(actual)
          .contains("<artifactId>" + artifact + "</artifactId>")
          .containsPattern("<version>5\\.\\d+\\.\\d+</version>")
          .actual()));
    }

    @DocumentExample
    @Test
    void convertsDao() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.BasicDBObject;
                  import com.mongodb.DB;
                  import com.mongodb.DBCollection;
                  import com.mongodb.DBCursor;
                  import com.mongodb.DBObject;
                  import com.mongodb.MongoClient;

                  import java.util.ArrayList;
                  import java.util.List;

                  class PersonDao {
                      private final MongoClient client = new MongoClient("localhost", 27017);
                      private final DB db = client.getDB("people");
                      private final DBCollection people = db.getCollection("person");

                      void add(String name) {
                          people.insert(new BasicDBObject("name", name));
                      }

                      DBObject byName(String name) {
                          return people.findOne(new BasicDBObject("name", name));
                      }

                      List<String> names() {
                          List<String> names = new ArrayList<>();
                          DBCursor cursor = people.find();
                          while (cursor.hasNext()) {
                              names.add((String) cursor.next().get("name"));
                          }
                          cursor.close();
                          return names;
                      }

                      long count() {
                          return people.count();
                      }

                      void remove(String name) {
                          people.remove(new BasicDBObject("name", name));
                      }

                      void close() {
                          client.close();
                      }
                  }
                  """,
                """
                  import com.mongodb.BasicDBObject;
                  import com.mongodb.DBObject;
                  import com.mongodb.client.MongoClient;
                  import com.mongodb.client.MongoClients;
                  import com.mongodb.client.MongoCollection;
                  import com.mongodb.client.MongoCursor;
                  import com.mongodb.client.MongoDatabase;

                  import java.util.ArrayList;
                  import java.util.List;

                  class PersonDao {
                      private final MongoClient client = MongoClients.create("mongodb://localhost:27017");
                      private final MongoDatabase db = client.getDatabase("people");
                      private final MongoCollection<DBObject> people = db.getCollection("person", DBObject.class);

                      void add(String name) {
                          people.insertOne(new BasicDBObject("name", name));
                      }

                      DBObject byName(String name) {
                          return people.find(new BasicDBObject("name", name)).first();
                      }

                      List<String> names() {
                          List<String> names = new ArrayList<>();
                          MongoCursor<DBObject> cursor = people.find().iterator();
                          while (cursor.hasNext()) {
                              names.add((String) cursor.next().get("name"));
                          }
                          cursor.close();
                          return names;
                      }

                      long count() {
                          return people.estimatedDocumentCount();
                      }

                      void remove(String name) {
                          people.deleteMany(new BasicDBObject("name", name));
                      }

                      void close() {
                          client.close();
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }
    @Test
    void convertsClientOptionsAndUri() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.MongoClient;
                  import com.mongodb.MongoClientOptions;
                  import com.mongodb.MongoClientURI;
                  import com.mongodb.MongoCredential;
                  import com.mongodb.ServerAddress;
                  import com.mongodb.WriteConcern;

                  import java.util.Arrays;

                  class Clients {
                      MongoClient fromOptions(String host, int port, String user, char[] password) {
                          MongoClientOptions options = MongoClientOptions.builder()
                                  .connectionsPerHost(50)
                                  .threadsAllowedToBlockForConnectionMultiplier(5)
                                  .connectTimeout(1000)
                                  .socketTimeout(2000)
                                  .socketKeepAlive(true)
                                  .writeConcern(WriteConcern.SAFE)
                                  .build();
                          MongoCredential credential = MongoCredential.createCredential(user, "admin", password);
                          return new MongoClient(new ServerAddress(host, port), Arrays.asList(credential), options);
                      }

                      MongoClient fromUri(String uri) {
                          return new MongoClient(new MongoClientURI(uri));
                      }
                  }
                  """,
                """
                  import com.mongodb.MongoClientSettings;
                  import com.mongodb.MongoCredential;
                  import com.mongodb.ServerAddress;
                  import com.mongodb.WriteConcern;
                  import com.mongodb.client.MongoClient;
                  import com.mongodb.client.MongoClients;

                  import java.util.Collections;
                  import java.util.concurrent.TimeUnit;

                  class Clients {
                      MongoClient fromOptions(String host, int port, String user, char[] password) {
                          MongoClientSettings options = MongoClientSettings.builder()
                                  .applyToConnectionPoolSettings(pool -> pool.maxSize(50))
                                  .applyToSocketSettings(socket -> socket.connectTimeout(1000, TimeUnit.MILLISECONDS).readTimeout(2000, TimeUnit.MILLISECONDS))
                                  .writeConcern(WriteConcern.ACKNOWLEDGED)
                                  .build();
                          MongoCredential credential = MongoCredential.createCredential(user, "admin", password);
                          return MongoClients.create(MongoClientSettings.builder(options).applyToClusterSettings(cluster -> cluster.hosts(Collections.singletonList(new ServerAddress(host, port)))).credential(credential).build());
                      }

                      MongoClient fromUri(String uri) {
                          return MongoClients.create(uri);
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void convertsUpdatesByTheShapeOfTheDocument() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.BasicDBObject;
                  import com.mongodb.DBCollection;
                  import com.mongodb.WriteResult;

                  class Updates {
                      int rename(DBCollection people, String from, String to) {
                          WriteResult result = people.update(new BasicDBObject("name", from),
                                  new BasicDBObject("$set", new BasicDBObject("name", to)), false, true);
                          return result.getN();
                      }

                      void replace(DBCollection people, String name, int age) {
                          people.update(new BasicDBObject("name", name), new BasicDBObject("name", name).append("age", age), true, false);
                      }

                      void touchAll(DBCollection people) {
                          people.updateMulti(new BasicDBObject(), new BasicDBObject("$inc", new BasicDBObject("visits", 1)));
                      }

                      int removeAll(DBCollection people) {
                          return people.remove(new BasicDBObject()).getN();
                      }
                  }
                  """,
                """
                  import com.mongodb.BasicDBObject;
                  import com.mongodb.DBObject;
                  import com.mongodb.client.MongoCollection;
                  import com.mongodb.client.model.ReplaceOptions;
                  import com.mongodb.client.result.UpdateResult;

                  class Updates {
                      int rename(MongoCollection<DBObject> people, String from, String to) {
                          UpdateResult result = people.updateMany(new BasicDBObject("name", from),
                                  new BasicDBObject("$set", new BasicDBObject("name", to)));
                          return (int) result.getMatchedCount();
                      }

                      void replace(MongoCollection<DBObject> people, String name, int age) {
                          people.replaceOne(new BasicDBObject("name", name), new BasicDBObject("name", name).append("age", age), new ReplaceOptions().upsert(true));
                      }

                      void touchAll(MongoCollection<DBObject> people) {
                          people.updateMany(new BasicDBObject(), new BasicDBObject("$inc", new BasicDBObject("visits", 1)));
                      }

                      int removeAll(MongoCollection<DBObject> people) {
                          return (int) people.deleteMany(new BasicDBObject()).getDeletedCount();
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void convertsQueriesCursorsAndCollections() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.BasicDBObject;
                  import com.mongodb.DB;
                  import com.mongodb.DBCollection;
                  import com.mongodb.DBObject;

                  import java.util.List;
                  import java.util.Set;

                  class Queries {
                      List<DBObject> oldest(DBCollection people, int n) {
                          return people.find(new BasicDBObject("active", true)).sort(new BasicDBObject("age", -1)).limit(n).toArray();
                      }

                      int printAll(DBCollection people) {
                          int count = 0;
                          for (DBObject person : people.find()) {
                              System.out.println(person);
                              count++;
                          }
                          return count;
                      }

                      long active(DBCollection people) {
                          return people.find(new BasicDBObject("active", true)).count();
                      }

                      List<String> names(DBCollection people) {
                          List<String> names = people.distinct("name");
                          return names;
                      }

                      void setUp(DB db) {
                          if (!db.collectionExists("people")) {
                              db.createCollection("people", null);
                          }
                          db.getCollection("people").createIndex("name");
                          Set<String> names = db.getCollectionNames();
                          System.out.println(names);
                      }
                  }
                  """,
                """
                  import com.mongodb.BasicDBObject;
                  import com.mongodb.DBObject;
                  import com.mongodb.client.MongoCollection;
                  import com.mongodb.client.MongoDatabase;

                  import java.util.ArrayList;
                  import java.util.List;
                  import java.util.Set;
                  import java.util.TreeSet;

                  class Queries {
                      List<DBObject> oldest(MongoCollection<DBObject> people, int n) {
                          return people.find(new BasicDBObject("active", true)).sort(new BasicDBObject("age", -1)).limit(n).into(new ArrayList<DBObject>());
                      }

                      int printAll(MongoCollection<DBObject> people) {
                          int count = 0;
                          for (DBObject person : people.find()) {
                              System.out.println(person);
                              count++;
                          }
                          return count;
                      }

                      long active(MongoCollection<DBObject> people) {
                          return people.countDocuments(new BasicDBObject("active", true));
                      }

                      List<String> names(MongoCollection<DBObject> people) {
                          List<String> names = people.distinct("name", String.class).into(new ArrayList<String>());
                          return names;
                      }

                      void setUp(MongoDatabase db) {
                          if (!db.listCollectionNames().into(new ArrayList<String>()).stream().anyMatch(("people")::equalsIgnoreCase)) {
                              db.createCollection("people");
                          }
                          db.getCollection("people", DBObject.class).createIndex(new BasicDBObject("name", 1));
                          Set<String> names = db.listCollectionNames().into(new TreeSet<String>());
                          System.out.println(names);
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void keepsJavaLegacyUuidRepresentation() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.BasicDBObject;
                  import com.mongodb.DBCollection;
                  import com.mongodb.MongoClient;

                  import java.util.UUID;

                  class Sessions {
                      DBCollection sessions = new MongoClient("db.example.com").getDB("app").getCollection("sessions");

                      void add(UUID id) {
                          sessions.insert(new BasicDBObject("_id", id));
                      }
                  }
                  """,
                """
                  import com.mongodb.BasicDBObject;
                  import com.mongodb.DBObject;
                  import com.mongodb.client.MongoClients;
                  import com.mongodb.client.MongoCollection;

                  import java.util.UUID;

                  class Sessions {
                      MongoCollection<DBObject> sessions = MongoClients.create("mongodb://db.example.com/?uuidRepresentation=javaLegacy").getDatabase("app").getCollection("sessions", DBObject.class);

                      void add(UUID id) {
                          sessions.insertOne(new BasicDBObject("_id", id));
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void keepsLegacyApiWhenAUseHasNoTranslation() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.DBCollection;
                  import com.mongodb.MongoClient;
                  import com.mongodb.WriteConcern;

                  import java.util.UUID;

                  class Orders {
                      MongoClient client = new MongoClient("localhost");

                      void configure(DBCollection orders) {
                          orders.setWriteConcern(WriteConcern.SAFE);
                      }

                      UUID id() {
                          return UUID.randomUUID();
                      }
                  }
                  """,
                """
                  import com.mongodb.DBCollection;
                  import com.mongodb.MongoClient;
                  import com.mongodb.MongoClientOptions;
                  import com.mongodb.WriteConcern;
                  import org.bson.UuidRepresentation;

                  import java.util.UUID;

                  class Orders {
                      MongoClient client = new MongoClient("localhost", MongoClientOptions.builder().uuidRepresentation(UuidRepresentation.JAVA_LEGACY).build());

                      void configure(DBCollection orders) {
                          orders.setWriteConcern(WriteConcern.ACKNOWLEDGED);
                      }

                      UUID id() {
                          return UUID.randomUUID();
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-legacy")
          )
        );
    }

    @Test
    void leavesDriverWhenARemovedApiHasNoReplacement() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.DB;
                  import com.mongodb.MongoClient;

                  class Scripts {
                      Object run(MongoClient client) {
                          DB db = client.getDB("app");
                          return db.eval("return 1");
                      }
                  }
                  """
              )
            ),
            pomXml(POM)
          )
        );
    }

    @Test
    void leavesRepositoriesAlreadyPastDriver3() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.BasicDBObject;
                  import com.mongodb.DBCollection;
                  import com.mongodb.client.MongoCollection;
                  import org.bson.Document;

                  class Counts {
                      long count(DBCollection people, MongoCollection<Document> docs) {
                          return people.count(new BasicDBObject("a", 1)) + docs.count();
                      }
                  }
                  """,
                spec -> spec.mapBeforeRecipe(cu -> (J.CompilationUnit) JavaSourceSet.updateOnSourceFile(cu,
                  sourceSet -> sourceSet.addTypesForGav("org.mongodb:mongodb-driver-legacy:4.11.5", emptyList())))
              )
            ),
            pomXml(POM.replace("mongo-java-driver", "mongodb-driver-legacy").replace("3.12.14", "4.11.5"))
          )
        );
    }

    @Test
    void leavesRepositoriesWhoseBuildResolvesADriverPast3() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.BasicDBObject;
                  import com.mongodb.DBCollection;

                  class Counts {
                      long count(DBCollection people) {
                          return people.count(new BasicDBObject("a", 1));
                      }
                  }
                  """
              )
            ),
            pomXml(POM.replace("mongo-java-driver", "mongodb-driver-legacy").replace("3.12.14", "4.11.5"))
          )
        );
    }

    @Test
    void migratesRemovedApiOfTheDriver5Api() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.Block;
                  import com.mongodb.client.MongoCollection;
                  import com.mongodb.client.model.UpdateOptions;
                  import com.mongodb.util.JSON;
                  import com.mongodb.DBObject;
                  import org.bson.Document;

                  class Modern {
                      long count(MongoCollection<Document> docs) {
                          return docs.count() + docs.count(new Document("a", 1));
                      }

                      void print(MongoCollection<Document> docs) {
                          docs.find().forEach(new Block<Document>() {
                              @Override
                              public void apply(Document document) {
                                  System.out.println(document);
                              }
                          });
                      }

                      void upsert(MongoCollection<Document> docs, Document doc) {
                          docs.replaceOne(new Document("_id", doc.get("_id")), doc, new UpdateOptions().upsert(true));
                      }

                      DBObject parse(String json) {
                          return (DBObject) JSON.parse(json);
                      }
                  }
                  """,
                """
                  import com.mongodb.client.MongoCollection;
                  import com.mongodb.client.model.ReplaceOptions;
                  import com.mongodb.BasicDBObject;
                  import com.mongodb.DBObject;
                  import org.bson.Document;

                  import java.util.function.Consumer;

                  class Modern {
                      long count(MongoCollection<Document> docs) {
                          return docs.estimatedDocumentCount() + docs.countDocuments(new Document("a", 1));
                      }

                      void print(MongoCollection<Document> docs) {
                          docs.find().forEach(new Consumer<Document>() {
                              @Override
                              public void accept(Document document) {
                                  System.out.println(document);
                              }
                          });
                      }

                      void upsert(MongoCollection<Document> docs, Document doc) {
                          docs.replaceOne(new Document("_id", doc.get("_id")), doc, new ReplaceOptions().upsert(true));
                      }

                      DBObject parse(String json) {
                          return BasicDBObject.parse(json);
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void renamesGetMongoOfALegacyDatabase() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.DB;
                  import com.mongodb.Mongo;

                  class Admin {
                      String describe(DB db) {
                          Mongo mongo = db.getMongo();
                          return mongo.toString();
                      }
                  }
                  """,
                """
                  import com.mongodb.DB;
                  import com.mongodb.MongoClient;

                  class Admin {
                      String describe(DB db) {
                          MongoClient mongo = db.getMongoClient();
                          return mongo.toString();
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-legacy")
          )
        );
    }

    @Test
    void takesTheCredentialOfAListAndTheSeedsOfTheSettings() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.MongoClient;
                  import com.mongodb.MongoClientOptions;
                  import com.mongodb.MongoCredential;
                  import com.mongodb.ServerAddress;

                  import java.util.ArrayList;
                  import java.util.Arrays;
                  import java.util.List;

                  class Clients {
                      MongoClient withList(ServerAddress address, MongoCredential credential, MongoClientOptions options) {
                          List<MongoCredential> credentials = new ArrayList<>();
                          credentials.add(credential);
                          return new MongoClient(address, credentials, options);
                      }

                      MongoClient withArray(List<ServerAddress> seeds, MongoCredential[] credentials) {
                          return new MongoClient(seeds, Arrays.asList(credentials));
                      }

                      MongoClient withNone(ServerAddress address) {
                          return new MongoClient(address, new ArrayList<MongoCredential>());
                      }

                      List<ServerAddress> seeds(MongoClient client) {
                          return client.getAllAddress();
                      }
                  }
                  """,
                """
                  import com.mongodb.MongoClient;
                  import com.mongodb.MongoClientOptions;
                  import com.mongodb.MongoCredential;
                  import com.mongodb.ServerAddress;

                  import java.util.ArrayList;
                  import java.util.List;

                  class Clients {
                      MongoClient withList(ServerAddress address, MongoCredential credential, MongoClientOptions options) {
                          List<MongoCredential> credentials = new ArrayList<>();
                          credentials.add(credential);
                          return new MongoClient(address, credentials.isEmpty() ? null : credentials.get(0), options);
                      }

                      MongoClient withArray(List<ServerAddress> seeds, MongoCredential[] credentials) {
                          return new MongoClient(seeds, credentials.length == 0 ? null : credentials[0], MongoClientOptions.builder().build());
                      }

                      MongoClient withNone(ServerAddress address) {
                          return new MongoClient(address);
                      }

                      List<ServerAddress> seeds(MongoClient client) {
                          return client.getClusterDescription().getClusterSettings().getHosts();
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-legacy")
          )
        );
    }

    @Test
    void convertsWhatReplacingRemovedApiMakesTranslatable() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.MongoClient;
                  import com.mongodb.ServerAddress;

                  import java.util.List;

                  class Seeds {
                      private final MongoClient client = new MongoClient("localhost", 27017);

                      List<ServerAddress> seeds() {
                          return client.getAllAddress();
                      }
                  }
                  """,
                """
                  import com.mongodb.ServerAddress;
                  import com.mongodb.client.MongoClient;
                  import com.mongodb.client.MongoClients;

                  import java.util.List;

                  class Seeds {
                      private final MongoClient client = MongoClients.create("mongodb://localhost:27017");

                      List<ServerAddress> seeds() {
                          return client.getClusterDescription().getClusterSettings().getHosts();
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void keepsVar() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.MongoClient;

                  class Cleanup {
                      void drop(String host, int port) {
                          var client = new MongoClient(host, port);
                          client.dropDatabase("test");
                          client.close();
                      }
                  }
                  """,
                """
                  import com.mongodb.client.MongoClients;

                  class Cleanup {
                      void drop(String host, int port) {
                          var client = MongoClients.create("mongodb://" + host + ":" + port);
                          client.getDatabase("test").drop();
                          client.close();
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void mergesTheSettingsOfTheOptionsAndTheHosts() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.MongoClient;
                  import com.mongodb.MongoClientOptions;
                  import com.mongodb.MongoCredential;
                  import com.mongodb.ServerAddress;

                  class Cleanup {
                      void drop(String host, int port, char[] password) {
                          try (MongoClient client = new MongoClient(new ServerAddress(host, port),
                                  MongoCredential.createCredential("root", "admin", password),
                                  MongoClientOptions.builder().serverSelectionTimeout(10_000).build())) {
                              client.dropDatabase("test");
                          }
                      }
                  }
                  """,
                """
                  import com.mongodb.MongoClientSettings;
                  import com.mongodb.MongoCredential;
                  import com.mongodb.ServerAddress;
                  import com.mongodb.client.MongoClient;
                  import com.mongodb.client.MongoClients;

                  import java.util.Collections;
                  import java.util.concurrent.TimeUnit;

                  class Cleanup {
                      void drop(String host, int port, char[] password) {
                          try (MongoClient client = MongoClients.create(MongoClientSettings.builder().applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(10_000, TimeUnit.MILLISECONDS).hosts(Collections.singletonList(new ServerAddress(host, port)))).credential(
                                  MongoCredential.createCredential("root", "admin", password)).build())) {
                              client.getDatabase("test").drop();
                          }
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void leavesSettingsBlocksThatAreNotCallChainsOnTheirBuilder() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.MongoClient;
                  import com.mongodb.MongoClientSettings;
                  import com.mongodb.ServerAddress;
                  import com.mongodb.connection.SslSettings;

                  import java.util.List;
                  import java.util.concurrent.TimeUnit;

                  class Clients {
                      private final List<ServerAddress> cluster;

                      Clients(List<ServerAddress> cluster) {
                          this.cluster = cluster;
                      }

                      MongoClientSettings settings(boolean secure) {
                          return MongoClientSettings.builder()
                                  .applyToSslSettings(ssl -> configure(ssl))
                                  .applyToSslSettings(ssl -> ssl.enabled(secure))
                                  .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(1, TimeUnit.SECONDS))
                                  .applyToClusterSettings(builder -> builder.hosts(cluster))
                                  .build();
                      }

                      static void configure(SslSettings.Builder ssl) {
                      }

                      void drop() {
                          new MongoClient("localhost").dropDatabase("test");
                      }
                  }
                  """,
                """
                  import com.mongodb.MongoClientSettings;
                  import com.mongodb.ServerAddress;
                  import com.mongodb.client.MongoClients;
                  import com.mongodb.connection.SslSettings;

                  import java.util.List;
                  import java.util.concurrent.TimeUnit;

                  class Clients {
                      private final List<ServerAddress> cluster;

                      Clients(List<ServerAddress> cluster) {
                          this.cluster = cluster;
                      }

                      MongoClientSettings settings(boolean secure) {
                          return MongoClientSettings.builder()
                                  .applyToSslSettings(ssl -> configure(ssl))
                                  .applyToSslSettings(ssl -> ssl.enabled(secure))
                                  .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(1, TimeUnit.SECONDS))
                                  .applyToClusterSettings(builder -> builder.hosts(cluster))
                                  .build();
                      }

                      static void configure(SslSettings.Builder ssl) {
                      }

                      void drop() {
                          MongoClients.create("mongodb://localhost").getDatabase("test").drop();
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void implementsTheMethodsThatDriver5AddsToItsTypes() {
        rewriteRun(
          spec -> spec.dataTable(MongoDriverMigrationDecisions.Row.class, rows -> assertThat(rows)
            .filteredOn(r -> "Added methods driver 5 requires".equals(r.getOutcome()))
            .extracting(MongoDriverMigrationDecisions.Row::getReason)
            .containsExactlyInAnyOrder(
              "`StubCursor` implements `MongoCursor`, to which driver 5 adds `available()`; the methods added throw `UnsupportedOperationException` until they are implemented",
              "`Indexes` implements `ListIndexesIterable`, to which driver 5 adds `comment(String)`, `comment(BsonValue)`, `timeoutMode(TimeoutMode)`; the methods added throw `UnsupportedOperationException` until they are implemented")),
          mavenProject("app",
            //language=java
            srcTestJava(
              java(
                """
                  import com.mongodb.Block;
                  import com.mongodb.Function;
                  import com.mongodb.ServerAddress;
                  import com.mongodb.ServerCursor;
                  import com.mongodb.client.ListIndexesIterable;
                  import com.mongodb.client.MongoCursor;
                  import com.mongodb.client.MongoIterable;
                  import org.bson.Document;

                  import java.util.Collection;
                  import java.util.Iterator;
                  import java.util.concurrent.TimeUnit;

                  class StubCursor implements MongoCursor<Document> {
                      private final Iterator<Document> documents;

                      StubCursor(Iterator<Document> documents) {
                          this.documents = documents;
                      }

                      @Override
                      public void close() {
                      }

                      @Override
                      public boolean hasNext() {
                          return documents.hasNext();
                      }

                      @Override
                      public Document next() {
                          return documents.next();
                      }

                      @Override
                      public Document tryNext() {
                          return hasNext() ? next() : null;
                      }

                      @Override
                      public ServerCursor getServerCursor() {
                          return null;
                      }

                      @Override
                      public ServerAddress getServerAddress() {
                          return null;
                      }
                  }

                  class Indexes<T> implements ListIndexesIterable<T> {
                      @Override
                      public ListIndexesIterable<T> maxTime(long maxTime, TimeUnit timeUnit) {
                          return this;
                      }

                      @Override
                      public ListIndexesIterable<T> batchSize(int batchSize) {
                          return this;
                      }

                      @Override
                      public MongoCursor<T> iterator() {
                          return cursor();
                      }

                      @Override
                      public MongoCursor<T> cursor() {
                          throw new UnsupportedOperationException();
                      }

                      @Override
                      public T first() {
                          return null;
                      }

                      @Override
                      public <U> MongoIterable<U> map(Function<T, U> mapper) {
                          throw new UnsupportedOperationException();
                      }

                      @Override
                      public void forEach(Block<? super T> block) {
                      }

                      @Override
                      public <A extends Collection<? super T>> A into(A target) {
                          return target;
                      }
                  }
                  """,
                """
                  import com.mongodb.Block;
                  import com.mongodb.Function;
                  import com.mongodb.ServerAddress;
                  import com.mongodb.ServerCursor;
                  import com.mongodb.client.ListIndexesIterable;
                  import com.mongodb.client.MongoCursor;
                  import com.mongodb.client.MongoIterable;
                  import com.mongodb.client.cursor.TimeoutMode;
                  import org.bson.BsonValue;
                  import org.bson.Document;

                  import java.util.Collection;
                  import java.util.Iterator;
                  import java.util.concurrent.TimeUnit;

                  class StubCursor implements MongoCursor<Document> {
                      private final Iterator<Document> documents;

                      StubCursor(Iterator<Document> documents) {
                          this.documents = documents;
                      }

                      @Override
                      public void close() {
                      }

                      @Override
                      public boolean hasNext() {
                          return documents.hasNext();
                      }

                      @Override
                      public Document next() {
                          return documents.next();
                      }

                      @Override
                      public Document tryNext() {
                          return hasNext() ? next() : null;
                      }

                      @Override
                      public ServerCursor getServerCursor() {
                          return null;
                      }

                      @Override
                      public ServerAddress getServerAddress() {
                          return null;
                      }

                      @Override
                      public int available() {
                          throw new UnsupportedOperationException();
                      }
                  }

                  class Indexes<T> implements ListIndexesIterable<T> {
                      @Override
                      public ListIndexesIterable<T> maxTime(long maxTime, TimeUnit timeUnit) {
                          return this;
                      }

                      @Override
                      public ListIndexesIterable<T> batchSize(int batchSize) {
                          return this;
                      }

                      @Override
                      public MongoCursor<T> iterator() {
                          return cursor();
                      }

                      @Override
                      public MongoCursor<T> cursor() {
                          throw new UnsupportedOperationException();
                      }

                      @Override
                      public T first() {
                          return null;
                      }

                      @Override
                      public <U> MongoIterable<U> map(Function<T, U> mapper) {
                          throw new UnsupportedOperationException();
                      }

                      public void forEach(Block<? super T> block) {
                      }

                      @Override
                      public <A extends Collection<? super T>> A into(A target) {
                          return target;
                      }

                      @Override
                      public ListIndexesIterable<T> comment(String comment) {
                          throw new UnsupportedOperationException();
                      }

                      @Override
                      public ListIndexesIterable<T> comment(BsonValue comment) {
                          throw new UnsupportedOperationException();
                      }

                      @Override
                      public ListIndexesIterable<T> timeoutMode(TimeoutMode timeoutMode) {
                          throw new UnsupportedOperationException();
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void implementsTheMethodsThatDriver5AddsWithTheTypeArgumentsOfTheClass() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcTestJava(
              java(
                """
                  import com.mongodb.Block;
                  import com.mongodb.Function;
                  import com.mongodb.client.ListIndexesIterable;
                  import com.mongodb.client.MongoCursor;
                  import com.mongodb.client.MongoIterable;
                  import org.bson.Document;

                  import java.util.Collection;
                  import java.util.concurrent.TimeUnit;

                  abstract class Base<T> implements ListIndexesIterable<T> {
                      @Override
                      public ListIndexesIterable<T> maxTime(long maxTime, TimeUnit timeUnit) {
                          return this;
                      }

                      @Override
                      public ListIndexesIterable<T> batchSize(int batchSize) {
                          return this;
                      }

                      @Override
                      public MongoCursor<T> iterator() {
                          return cursor();
                      }

                      @Override
                      public MongoCursor<T> cursor() {
                          throw new UnsupportedOperationException();
                      }

                      @Override
                      public T first() {
                          return null;
                      }

                      @Override
                      public <U> MongoIterable<U> map(Function<T, U> mapper) {
                          throw new UnsupportedOperationException();
                      }

                      @Override
                      public void forEach(Block<? super T> block) {
                      }

                      @Override
                      public <A extends Collection<? super T>> A into(A target) {
                          return target;
                      }
                  }

                  class Documents extends Base<Document> {
                  }
                  """,
                """
                  import com.mongodb.Block;
                  import com.mongodb.Function;
                  import com.mongodb.client.ListIndexesIterable;
                  import com.mongodb.client.MongoCursor;
                  import com.mongodb.client.MongoIterable;
                  import com.mongodb.client.cursor.TimeoutMode;
                  import org.bson.BsonValue;
                  import org.bson.Document;

                  import java.util.Collection;
                  import java.util.concurrent.TimeUnit;

                  abstract class Base<T> implements ListIndexesIterable<T> {
                      @Override
                      public ListIndexesIterable<T> maxTime(long maxTime, TimeUnit timeUnit) {
                          return this;
                      }

                      @Override
                      public ListIndexesIterable<T> batchSize(int batchSize) {
                          return this;
                      }

                      @Override
                      public MongoCursor<T> iterator() {
                          return cursor();
                      }

                      @Override
                      public MongoCursor<T> cursor() {
                          throw new UnsupportedOperationException();
                      }

                      @Override
                      public T first() {
                          return null;
                      }

                      @Override
                      public <U> MongoIterable<U> map(Function<T, U> mapper) {
                          throw new UnsupportedOperationException();
                      }

                      public void forEach(Block<? super T> block) {
                      }

                      @Override
                      public <A extends Collection<? super T>> A into(A target) {
                          return target;
                      }
                  }

                  class Documents extends Base<Document> {
                      @Override
                      public ListIndexesIterable<Document> comment(String comment) {
                          throw new UnsupportedOperationException();
                      }

                      @Override
                      public ListIndexesIterable<Document> comment(BsonValue comment) {
                          throw new UnsupportedOperationException();
                      }

                      @Override
                      public ListIndexesIterable<Document> timeoutMode(TimeoutMode timeoutMode) {
                          throw new UnsupportedOperationException();
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void implementsTheMethodsThatDriver5AddsInAnAnonymousClass() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcTestJava(
              java(
                """
                  import com.mongodb.ServerAddress;
                  import com.mongodb.ServerCursor;
                  import com.mongodb.client.MongoCursor;
                  import org.bson.Document;

                  import java.util.NoSuchElementException;

                  class Cursors {
                      MongoCursor<Document> empty() {
                          return new MongoCursor<Document>() {
                              @Override
                              public boolean hasNext() {
                                  return false;
                              }

                              @Override
                              public Document next() {
                                  throw new NoSuchElementException();
                              }

                              @Override
                              public Document tryNext() {
                                  return null;
                              }

                              @Override
                              public void close() {
                              }

                              @Override
                              public ServerCursor getServerCursor() {
                                  return null;
                              }

                              @Override
                              public ServerAddress getServerAddress() {
                                  return null;
                              }
                          };
                      }
                  }
                  """,
                """
                  import com.mongodb.ServerAddress;
                  import com.mongodb.ServerCursor;
                  import com.mongodb.client.MongoCursor;
                  import org.bson.Document;

                  import java.util.NoSuchElementException;

                  class Cursors {
                      MongoCursor<Document> empty() {
                          return new MongoCursor<Document>() {
                              @Override
                              public boolean hasNext() {
                                  return false;
                              }

                              @Override
                              public Document next() {
                                  throw new NoSuchElementException();
                              }

                              @Override
                              public Document tryNext() {
                                  return null;
                              }

                              @Override
                              public void close() {
                              }

                              @Override
                              public ServerCursor getServerCursor() {
                                  return null;
                              }

                              @Override
                              public ServerAddress getServerAddress() {
                                  return null;
                              }

                              @Override
                              public int available() {
                                  throw new UnsupportedOperationException();
                              }
                          };
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void leavesDriverWhenAClassImplementsTypesThatDriver5AddsInternalApiTo() {
        rewriteRun(
          spec -> spec.dataTable(MongoDriverMigrationDecisions.Row.class, rows -> assertThat(rows)
            .extracting(MongoDriverMigrationDecisions.Row::getReason)
            .anyMatch(r -> r.startsWith("declares `Stub`, which implements `ClientSession`, of which driver 5 requires") &&
                           r.endsWith("which names a type of its internal API"))),
          mavenProject("app",
            //language=java
            srcTestJava(
              java(
                """
                  import com.mongodb.client.ClientSession;

                  abstract class Session implements ClientSession {
                  }

                  class Stub extends Session {
                  }
                  """
              )
            ),
            pomXml(POM)
          )
        );
    }

    @Test
    void implementsTheCodecRegistryAsDriver3AdaptedIt() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import org.bson.codecs.Codec;
                  import org.bson.codecs.configuration.CodecConfigurationException;
                  import org.bson.codecs.configuration.CodecRegistry;

                  class Registry implements CodecRegistry {
                      @Override
                      public <T> Codec<T> get(Class<T> clazz) {
                          throw new CodecConfigurationException("No codec for " + clazz);
                      }
                  }
                  """,
                """
                  import org.bson.codecs.Codec;
                  import org.bson.codecs.configuration.CodecConfigurationException;
                  import org.bson.codecs.configuration.CodecRegistry;

                  class Registry implements CodecRegistry {
                      @Override
                      public <T> Codec<T> get(Class<T> clazz) {
                          throw new CodecConfigurationException("No codec for " + clazz);
                      }

                      @Override
                      public <T> Codec<T> get(Class<T> clazz, CodecRegistry registry) {
                          try {
                              return get(clazz);
                          } catch (CodecConfigurationException e) {
                              return null;
                          }
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void leavesDriverWhenTheDriverCallsMethodsThatDriver5AddsToAClass() {
        rewriteRun(
          spec -> spec.dataTable(MongoDriverMigrationDecisions.Row.class, rows -> assertThat(rows)
            .extracting(MongoDriverMigrationDecisions.Row::getReason)
            .anyMatch(r -> r.startsWith("declares `HeapBuffer`, which implements `ByteBuf`, of which driver 5 requires") &&
                           r.endsWith("which the driver calls on it, so that a method that throws cannot stand in"))),
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import org.bson.ByteBuf;

                  abstract class Buffer implements ByteBuf {
                  }

                  class HeapBuffer extends Buffer {
                  }
                  """
              )
            ),
            pomXml(POM)
          )
        );
    }

    @Test
    void implementsTheMethodsThatTheDriverOfTheClassLacked() {
        // MongoIterable gained cursor() in 3.11, which a class written for driver 3.10 lacks
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.Block;
                  import com.mongodb.Function;
                  import com.mongodb.client.ListIndexesIterable;
                  import com.mongodb.client.MongoCursor;
                  import com.mongodb.client.MongoIterable;
                  import org.bson.Document;

                  import java.util.Collection;
                  import java.util.concurrent.TimeUnit;

                  class NoIndexes implements ListIndexesIterable<Document> {
                      @Override
                      public ListIndexesIterable<Document> maxTime(long maxTime, TimeUnit timeUnit) {
                          return this;
                      }

                      @Override
                      public ListIndexesIterable<Document> batchSize(int batchSize) {
                          return this;
                      }

                      @Override
                      public MongoCursor<Document> iterator() {
                          throw new UnsupportedOperationException();
                      }

                      @Override
                      public Document first() {
                          return null;
                      }

                      @Override
                      public <U> MongoIterable<U> map(Function<Document, U> mapper) {
                          throw new UnsupportedOperationException();
                      }

                      @Override
                      public void forEach(Block<? super Document> block) {
                      }

                      @Override
                      public <A extends Collection<? super Document>> A into(A target) {
                          return target;
                      }
                  }
                  """,
                """
                  import com.mongodb.Block;
                  import com.mongodb.Function;
                  import com.mongodb.client.ListIndexesIterable;
                  import com.mongodb.client.MongoCursor;
                  import com.mongodb.client.MongoIterable;
                  import com.mongodb.client.cursor.TimeoutMode;
                  import org.bson.BsonValue;
                  import org.bson.Document;

                  import java.util.Collection;
                  import java.util.concurrent.TimeUnit;

                  class NoIndexes implements ListIndexesIterable<Document> {
                      @Override
                      public ListIndexesIterable<Document> maxTime(long maxTime, TimeUnit timeUnit) {
                          return this;
                      }

                      @Override
                      public ListIndexesIterable<Document> batchSize(int batchSize) {
                          return this;
                      }

                      @Override
                      public MongoCursor<Document> iterator() {
                          throw new UnsupportedOperationException();
                      }

                      @Override
                      public Document first() {
                          return null;
                      }

                      @Override
                      public <U> MongoIterable<U> map(Function<Document, U> mapper) {
                          throw new UnsupportedOperationException();
                      }

                      public void forEach(Block<? super Document> block) {
                      }

                      @Override
                      public <A extends Collection<? super Document>> A into(A target) {
                          return target;
                      }

                      @Override
                      public ListIndexesIterable<Document> comment(String comment) {
                          throw new UnsupportedOperationException();
                      }

                      @Override
                      public ListIndexesIterable<Document> comment(BsonValue comment) {
                          throw new UnsupportedOperationException();
                      }

                      @Override
                      public MongoCursor<Document> cursor() {
                          throw new UnsupportedOperationException();
                      }

                      @Override
                      public ListIndexesIterable<Document> timeoutMode(TimeoutMode timeoutMode) {
                          throw new UnsupportedOperationException();
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void leavesDriverWhenAClassExtendsADriverTypeRaw() {
        rewriteRun(
          spec -> spec.dataTable(MongoDriverMigrationDecisions.Row.class, rows -> assertThat(rows)
            .extracting(MongoDriverMigrationDecisions.Row::getReason)
            .anyMatch(r -> r.startsWith("declares `Raw`, which implements `ListIndexesIterable`, of which driver 5 requires") &&
                           r.endsWith("whose type arguments the class leaves unbound"))),
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.client.ListIndexesIterable;

                  abstract class Indexes<T> implements ListIndexesIterable<T> {
                  }

                  @SuppressWarnings("rawtypes")
                  class Raw extends Indexes {
                  }
                  """
              )
            ),
            pomXml(POM)
          )
        );
    }

    @Test
    void leavesDriverWhenAClassReturnsNothingWhereDriver5ReturnsAResult() {
        rewriteRun(
          spec -> spec.dataTable(MongoDriverMigrationDecisions.Row.class, rows -> assertThat(rows)
            .extracting(MongoDriverMigrationDecisions.Row::getReason)
            .contains("declares `Inserts`, which implements `MongoCollection`, of which driver 5 requires `insertOne(Object)`, " +
                      "which it declares to return a value, where the class's returns nothing")),
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.client.MongoCollection;

                  abstract class Collection<T> implements MongoCollection<T> {
                  }

                  class Inserts<T> extends Collection<T> {
                      @Override
                      public void insertOne(T document) {
                      }
                  }
                  """
              )
            ),
            pomXml(POM)
          )
        );
    }

    @Test
    void keepsTheConnectionStringThatToStringReturned() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.MongoClientURI;

                  class Uris {
                      String normalize(String text) {
                          MongoClientURI uri = new MongoClientURI(text);
                          return uri.toString();
                      }
                  }
                  """,
                """
                  import com.mongodb.ConnectionString;

                  class Uris {
                      String normalize(String text) {
                          ConnectionString uri = new ConnectionString(text);
                          return uri.getConnectionString();
                      }
                  }
                  """
              ),
              java(
                """
                  import com.mongodb.ConnectionString;

                  class Connections {
                      String describe(ConnectionString connection) {
                          return connection.toString();
                      }
                  }
                  """,
                """
                  import com.mongodb.ConnectionString;

                  class Connections {
                      String describe(ConnectionString connection) {
                          return connection.getConnectionString();
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void keepsTheStrictJsonThatDriver3Wrote() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.BasicDBObject;
                  import com.mongodb.client.MongoCollection;
                  import org.bson.Document;

                  class Json {
                      String first(MongoCollection<Document> people) {
                          return people.find().first().toJson();
                      }

                      String describe(BasicDBObject person) {
                          return person.toString();
                      }

                      String label(Person person) {
                          return person.toString();
                      }
                  }

                  class Person extends BasicDBObject {
                      @Override
                      public String toString() {
                          return getString("name");
                      }
                  }
                  """,
                """
                  import com.mongodb.BasicDBObject;
                  import com.mongodb.client.MongoCollection;
                  import org.bson.Document;
                  import org.bson.json.JsonMode;
                  import org.bson.json.JsonWriterSettings;

                  class Json {
                      String first(MongoCollection<Document> people) {
                          return people.find().first().toJson(JsonWriterSettings.builder().outputMode(JsonMode.STRICT).build());
                      }

                      String describe(BasicDBObject person) {
                          return person.toJson(JsonWriterSettings.builder().outputMode(JsonMode.STRICT).build());
                      }

                      String label(Person person) {
                          return person.toString();
                      }
                  }

                  class Person extends BasicDBObject {
                      @Override
                      public String toString() {
                          return getString("name");
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void keepsTheStrictJsonOnlyWhereDriver3WroteItAndListsWhereItCannot() {
        String relaxed = " into a string, which driver 5 writes as relaxed extended JSON where driver 3 wrote strict";
        rewriteRun(
          spec -> spec.dataTable(MongoDriverMigrationDecisions.Row.class, rows -> assertThat(rows)
            .filteredOn(r -> r.getReason().endsWith(relaxed))
            .extracting(MongoDriverMigrationDecisions.Row::getReason)
            .containsExactlyInAnyOrder(
              "`\"q=\" + query` turns a `BasicDBObject`" + relaxed,
              "`query.toString()` turns a `DBObject`" + relaxed,
              "`String.valueOf(query)` turns a `BasicDBObject`" + relaxed)),
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.BasicDBObject;
                  import com.mongodb.DBObject;

                  class Queries {
                      String describe(BasicDBObject query) {
                          return "q=" + query;
                      }

                      String text(DBObject query) {
                          return query.toString();
                      }

                      String value(BasicDBObject query) {
                          return String.valueOf(query);
                      }

                      String label(Named named) {
                          return "named " + named + ": " + named.toString();
                      }
                  }

                  class Named extends BasicDBObject {
                      @Override
                      public String toJson() {
                          return getString("name");
                      }
                  }

                  class Logged extends BasicDBObject {
                      @Override
                      public String toString() {
                          return "logged " + super.toString();
                      }
                  }
                  """,
                """
                  import com.mongodb.BasicDBObject;
                  import com.mongodb.DBObject;
                  import org.bson.json.JsonMode;
                  import org.bson.json.JsonWriterSettings;

                  class Queries {
                      String describe(BasicDBObject query) {
                          return "q=" + query;
                      }

                      String text(DBObject query) {
                          return query.toString();
                      }

                      String value(BasicDBObject query) {
                          return String.valueOf(query);
                      }

                      String label(Named named) {
                          return "named " + named + ": " + named.toString();
                      }
                  }

                  class Named extends BasicDBObject {
                      @Override
                      public String toJson() {
                          return getString("name");
                      }
                  }

                  class Logged extends BasicDBObject {
                      @Override
                      public String toString() {
                          return "logged " + super.toJson(JsonWriterSettings.builder().outputMode(JsonMode.STRICT).build());
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void keepsTheLineBreaksOfTheFile(boolean repositoryUsesCrlf) {
        // The style detected for a whole repository, which lays out the imports added, can differ from a file's own
        String fileBreak = repositoryUsesCrlf ? "\n" : "\r\n";
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion()
            .styles(singletonList(new NamedStyles(Tree.randomId(), "repository", "Repository", null, emptySet(),
              List.of(IntelliJ.importLayout().withClassCountToUseStarImport(999).withNameCountToUseStarImport(999),
                new GeneralFormatStyle(repositoryUsesCrlf)))))
            .classpathFromResources(new InMemoryExecutionContext(), "mongo-java-driver")),
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import org.bson.Document;

                  class Json {
                      String json(Document person) {
                          return person.toJson();
                      }
                  }
                  """.replace("\n", fileBreak),
                """
                  import org.bson.Document;
                  import org.bson.json.JsonMode;
                  import org.bson.json.JsonWriterSettings;

                  class Json {
                      String json(Document person) {
                          return person.toJson(JsonWriterSettings.builder().outputMode(JsonMode.STRICT).build());
                      }
                  }
                  """.replace("\n", fileBreak)
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void requiresTheModulesOfDriver5() {
        rewriteRun(
          mavenProject("app",
            srcMainJava(
              //language=java
              java(
                """
                  import com.mongodb.client.MongoCollection;
                  import org.bson.Document;

                  class Sessions {
                      long count(MongoCollection<Document> sessions) {
                          return sessions.count();
                      }
                  }
                  """,
                """
                  import com.mongodb.client.MongoCollection;
                  import org.bson.Document;

                  class Sessions {
                      long count(MongoCollection<Document> sessions) {
                          return sessions.estimatedDocumentCount();
                      }
                  }
                  """
              ),
              text(
                """
                  module org.example.sessions
                  {
                      requires transitive mongo.java.driver;
                      requires org.mongodb.bson;
                      requires transitive org.eclipse.jetty.server;
                  }
                  """,
                """
                  module org.example.sessions
                  {
                      requires transitive org.mongodb.driver.sync.client;
                      requires transitive org.mongodb.driver.core;
                      requires org.mongodb.bson;
                      requires transitive org.eclipse.jetty.server;
                  }
                  """,
                spec -> spec.path("module-info.java")
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void leavesDriverOfAJavaModuleThatKeepsTheLegacyApi() {
        rewriteRun(
          spec -> spec.dataTable(MongoDriverMigrationDecisions.Row.class, rows -> assertThat(rows)
            .extracting(MongoDriverMigrationDecisions.Row::getReason)
            .anyMatch(r -> r.contains("declares the Java module app/src/main/java/module-info.java, which cannot read the legacy API"))),
          mavenProject("app",
            srcMainJava(
              //language=java
              java(
                """
                  import com.mongodb.DBCollection;
                  import com.mongodb.WriteConcern;

                  class Orders {
                      void configure(DBCollection orders) {
                          orders.setWriteConcern(WriteConcern.ACKNOWLEDGED);
                      }
                  }
                  """
              ),
              text(
                """
                  module org.example.orders {
                      requires transitive mongo.java.driver;
                  }
                  """,
                spec -> spec.path("module-info.java")
              )
            ),
            pomXml(POM)
          )
        );
    }

    @Test
    void removesUnusedImportsThatDriver5CannotResolve() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.MongoClient;

                  class Cleanup {
                      void drop(MongoClient client) {
                          client.dropDatabase("test");
                      }
                  }
                  """,
                """
                  import com.mongodb.client.MongoClient;

                  class Cleanup {
                      void drop(MongoClient client) {
                          client.getDatabase("test").drop();
                      }
                  }
                  """
              ),
              java(
                """
                  import com.mongodb.MongoClient;
                  import com.mongodb.util.JSON;
                  import com.mongodb.util.*;

                  import java.util.List;

                  class Leftovers {
                      List<String> names;
                  }
                  """,
                """
                  import java.util.List;

                  class Leftovers {
                      List<String> names;
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void pinsThePartsOfTheDriverThatAParentHoldsBack() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.MongoClient;

                  class Cleanup {
                      void drop(MongoClient client) {
                          client.dropDatabase("test");
                      }
                  }
                  """,
                """
                  import com.mongodb.client.MongoClient;

                  class Cleanup {
                      void drop(MongoClient client) {
                          client.getDatabase("test").drop();
                      }
                  }
                  """
              )
            ),
            //language=xml
            pomXml(
              """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>2.1.5.RELEASE</version>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.mongodb</groupId>
                            <artifactId>mongo-java-driver</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """,
              spec -> spec.after(actual -> assertThat(actual)
                .contains("<artifactId>mongodb-driver-sync</artifactId>")
                .containsPattern("(?s)<dependencyManagement>.*<artifactId>bson</artifactId>\\s*<version>5\\.\\d+\\.\\d+</version>")
                .containsPattern("(?s)<dependencyManagement>.*<artifactId>mongodb-driver-core</artifactId>\\s*<version>5\\.\\d+\\.\\d+</version>")
                .actual())
            )
          )
        );
    }

    @Test
    void findsRemovedApiWithoutReplacement() {
        rewriteRun(
          spec -> spec.recipe(new FindRemovedMongoApis(null)),
          //language=java
          java(
            """
              import com.mongodb.DB;
              import com.mongodb.WriteConcern;

              class Scripts {
                  Object run(DB db) {
                      db.getCollection("a").setWriteConcern(WriteConcern.SAFE);
                      return db.eval("return 1");
                  }
              }
              """,
            """
              import com.mongodb.DB;
              import com.mongodb.WriteConcern;

              class Scripts {
                  Object run(DB db) {
                      db.getCollection("a").setWriteConcern(WriteConcern.SAFE);
                      return /*~~(Removed in driver 5: `DB.eval`)~~>*/db.eval("return 1");
                  }
              }
              """
          )
        );
    }
    @Test
    void convertsSaveFindAndModifyAndCommands() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.BasicDBObject;
                  import com.mongodb.CommandResult;
                  import com.mongodb.DB;
                  import com.mongodb.DBCollection;
                  import com.mongodb.DBObject;

                  class Store {
                      void save(DBCollection items, DBObject item) {
                          items.save(item);
                      }

                      DBObject next(DBCollection counters, String name) {
                          return counters.findAndModify(new BasicDBObject("_id", name), null, null, false,
                                  new BasicDBObject("$inc", new BasicDBObject("seq", 1)), true, true);
                      }

                      int version(DB db) {
                          CommandResult info = db.command("buildInfo");
                          return info.getInt("versionArray");
                      }
                  }
                  """,
                """
                  import com.mongodb.BasicDBObject;
                  import com.mongodb.DBObject;
                  import com.mongodb.client.MongoCollection;
                  import com.mongodb.client.MongoDatabase;
                  import com.mongodb.client.model.FindOneAndUpdateOptions;
                  import com.mongodb.client.model.ReplaceOptions;
                  import com.mongodb.client.model.ReturnDocument;

                  class Store {
                      void save(MongoCollection<DBObject> items, DBObject item) {
                          if (item.get("_id") == null) {
                              items.insertOne(item);
                          } else {
                              items.replaceOne(new BasicDBObject("_id", item.get("_id")), item, new ReplaceOptions().upsert(true));
                          }
                      }

                      DBObject next(MongoCollection<DBObject> counters, String name) {
                          return counters.findOneAndUpdate(new BasicDBObject("_id", name),
                                  new BasicDBObject("$inc", new BasicDBObject("seq", 1)), new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER).upsert(true));
                      }

                      int version(MongoDatabase db) {
                          BasicDBObject info = db.runCommand(new BasicDBObject("buildInfo", 1), BasicDBObject.class);
                          return info.getInt("versionArray");
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void convertsOptionsBuiltStatementByStatement() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.MongoClient;
                  import com.mongodb.MongoClientOptions;
                  import com.mongodb.ServerAddress;

                  class Factory {
                      MongoClient create(String host) {
                          MongoClientOptions.Builder builder = new MongoClientOptions.Builder();
                          builder.connectionsPerHost(20);
                          builder.socketKeepAlive(true);
                          builder.serverSelectionTimeout(5000);
                          return new MongoClient(new ServerAddress(host), builder.build());
                      }
                  }
                  """,
                """
                  import com.mongodb.MongoClientSettings;
                  import com.mongodb.ServerAddress;
                  import com.mongodb.client.MongoClient;
                  import com.mongodb.client.MongoClients;

                  import java.util.Collections;
                  import java.util.concurrent.TimeUnit;

                  class Factory {
                      MongoClient create(String host) {
                          MongoClientSettings.Builder builder = MongoClientSettings.builder();
                          builder.applyToConnectionPoolSettings(pool -> pool.maxSize(20));
                          builder.applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(5000, TimeUnit.MILLISECONDS));
                          return MongoClients.create(builder.applyToClusterSettings(cluster -> cluster.hosts(Collections.singletonList(new ServerAddress(host)))).build());
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-sync")
          )
        );
    }

    @Test
    void keepsLegacyApiThatAFrameworkInjects() {
        rewriteRun(
          spec -> spec.parser(parser("mongo-java-driver", "spring-context")),
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.mongodb.DBCollection;
                  import com.mongodb.MongoClient;
                  import org.springframework.stereotype.Component;

                  @Component
                  class Repository {
                      private final MongoClient client;

                      Repository(MongoClient client) {
                          this.client = client;
                      }

                      DBCollection people() {
                          return client.getDB("app").getCollection("people");
                      }
                  }
                  """
              )
            ),
            pom("mongodb-driver-legacy")
          )
        );
    }
}
