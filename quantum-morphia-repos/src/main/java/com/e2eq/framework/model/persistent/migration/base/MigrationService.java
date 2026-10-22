package com.e2eq.framework.model.persistent.migration.base;

import com.coditory.sherlock.DistributedLock;
import com.coditory.sherlock.Sherlock;
import com.coditory.sherlock.mongo.MongoSherlock;
import com.e2eq.framework.model.persistent.morphia.ChangeSetRecordRepo;
import com.e2eq.framework.model.persistent.morphia.DatabaseVersionRepo;

import com.e2eq.framework.model.persistent.morphia.DeclaredIndexes;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.exceptions.DatabaseMigrationException;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.util.SecurityUtils;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import dev.morphia.Datastore;
import dev.morphia.MorphiaDatastore;
import dev.morphia.annotations.internal.IndexHelper;
import dev.morphia.mapping.codec.pojo.EntityModel;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.semver4j.Semver;



import java.util.*;
import java.util.ArrayList;


@ApplicationScoped
public class MigrationService {

   @ConfigProperty(name = "quantum.database.version")
   protected String targetDatabaseVersion;

   @ConfigProperty(name = "quantum.database.scope")
   protected String databaseScope;
   @ConfigProperty(name = "quantum.realmConfig.defaultRealm", defaultValue = "mycompanyxyz-com")
   protected String defaultRealm;
   @ConfigProperty(name = "quantum.realmConfig.testRealm", defaultValue = "test-system-com")
   protected String testRealm;
   /** System realm database name; must match quantum.realmConfig.systemRealm in application.properties (e.g. system-com). */
   @ConfigProperty(name = "quantum.realmConfig.systemRealm", defaultValue = "system-com")
   protected String systemRealm;

   @ConfigProperty(name = "quantum.database.migration.enabled")
   protected boolean enabled;

   /**
    * How long a realm's migration lock is held. It must exceed the longest expected migration call
    * (pending change sets plus the index step), otherwise another caller can acquire it mid-run.
    */
   @ConfigProperty(name = "quantum.database.migration.lock-duration", defaultValue = "PT30M")
   protected java.time.Duration migrationLockDuration = java.time.Duration.ofMinutes(30);

   /**
    * Optional comma-separated list of additional realms to initialize on startup before seed packs run.
    * When unset, startup migration falls back to the historical behavior:
    * always evaluate the system realm and evaluate the default realm when it exists.
    *
    * Example: "system-com,mycompany-com,test-com"
    */
   @ConfigProperty(name = "quantum.migration.apply.realms")
   Optional<String> startupRealmsCsv;

   @Inject
   DatabaseVersionRepo databaseVersionRepo;

   @Inject
   SecurityUtils securityUtils;

   @Inject
   BeanManager beanManager;

   @Inject
   ChangeSetRecordRepo changesetRecordRepo;

   @Inject
   MongoClient mongoClient;

   @Inject
   MorphiaDataStoreWrapper morphiaDataStoreWrapper;

   public void initializeStartupRealms() {
      if (!enabled) {
         Log.warn("!!!! >>>> Database migration is disabled by configuration (quantum.database.migration.enabled=false)");
         return;
      } else {
         Log.info(">> Migration Service enabled");
      }

      List<String> databaseNames = mongoClient.listDatabaseNames().into(new ArrayList<>());
      List<String> startupRealms = resolveStartupRealms(databaseNames);
      Log.infof(">> Migration startup realms: %s", startupRealms);
      for (String realm : startupRealms) {
         ensureRealmInitializedOnStartup(realm, databaseNames.contains(realm));
      }
   }

   List<String> resolveStartupRealms(List<String> existingDatabaseNames) {
      LinkedHashSet<String> realms = new LinkedHashSet<>();

      realms.add(systemRealm);
      if (existingDatabaseNames.contains(defaultRealm)) {
         realms.add(defaultRealm);
      }

      if (startupRealmsCsv.isPresent()) {
         String csv = startupRealmsCsv.get().trim();
         if (!csv.isEmpty() && !csv.equalsIgnoreCase("none")) {
            for (String part : csv.split(",")) {
               String realm = part.trim();
               if (!realm.isEmpty()) {
                  realms.add(realm);
               }
            }
         }
      }

      return new ArrayList<>(realms);
   }

   private void ensureRealmInitializedOnStartup(String realm, boolean realmExists) {
      if (!realmExists) {
         Log.warnf("    ### Realm %s does not exist, creating it", realm);
         migrateRealmSynchronously(realm);
         return;
      }

      Log.infof("### Realm %s exists, checking if it is initialized", realm);
      try {
         checkInitialized(realm);
      } catch (DatabaseMigrationException e) {
         Log.warnf("    ### Realm %s is not initialized or is out of date (%s), initializing it", realm, e.getMessage());
         migrateRealmSynchronously(realm);
      }
   }

   private void migrateRealmSynchronously(String realm) {
      MultiEmitter<String> emitter = newLoggingEmitter();
      SecurityCallScope.runWithContexts(
         securityUtils.getSystemPrincipalContext(),
         securityUtils.getSystemSecurityResourceContext(),
         () -> {
            try {
               runAllUnRunMigrations(realm, emitter);
            } catch (MigrationInProgressException e) {
               // Another replica or the deploy pipeline owns initialization. Request-time migration
               // checks still reject work against an outdated realm until that run completes.
               Log.warnf("Startup migration for realm %s is already running; continuing startup", realm);
            } catch (IndexBuildException e) {
               // Startup stays available; the deploy pipeline's migration call for this realm
               // fails on the same error, and every migration call retries the index step.
               Log.errorf(e, "Startup migration for realm %s completed but declared indexes could not all be applied", realm);
            }
         }
      );
   }

   @SuppressWarnings("unchecked")
   private MultiEmitter<String> newLoggingEmitter() {
      return (MultiEmitter<String>) java.lang.reflect.Proxy.newProxyInstance(
         MultiEmitter.class.getClassLoader(),
         new Class[]{MultiEmitter.class},
         (proxy, method, args) -> {
            String name = method.getName();
            if ("emit".equals(name) && args != null && args.length == 1 && args[0] instanceof String value) {
               Log.info(value);
            } else if ("fail".equals(name) && args != null && args.length == 1 && args[0] instanceof Throwable failure) {
               Log.error("Migration emitter failure", failure);
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == Void.TYPE) return null;
            if (returnType == boolean.class || returnType == Boolean.class) return Boolean.FALSE;
            if (returnType == long.class || returnType == Long.class) return 0L;
            if (MultiEmitter.class.isAssignableFrom(returnType)) return proxy;
            return null;
         }
      );
   }


   public void checkInitialized (String realm) {
      Optional<DatabaseVersion> oDatabaseVersion = databaseVersionRepo.findCurrentVersion(realm);
      if (!oDatabaseVersion.isPresent()) {
         throw new DatabaseMigrationException(String.format("Database Version was not found, for realm: %s. Please initialize / run migrations on the database", realm));
      }
      DatabaseVersion currentVersion = oDatabaseVersion.get();
      Semver currentSemVersion = currentVersion.getCurrentSemVersion();
      Semver requiredSemVersion = Semver.parse(targetDatabaseVersion);
      if (currentSemVersion.compareTo(requiredSemVersion) < 0) {
         throw new DatabaseMigrationException(realm, currentVersion.getCurrentVersionString(), targetDatabaseVersion);
      }

      // Check for pending changesets with mismatched checksums or version bumps
      Datastore datastore = morphiaDataStoreWrapper.getDataStore(realm);
      List<ChangeSetBean> allChangeSets = getAllChangeSetBeans();
      for (ChangeSetBean chb : allChangeSets) {
         // Only check changesets applicable to this realm
         Set<String> applicable = chb.getApplicableDatabases();
         if (applicable != null && !applicable.isEmpty() && !applicable.contains(realm)) {
            continue;
         }
         Optional<ChangeSetRecord> record = changesetRecordRepo.findLatestByChangeSetName(datastore, chb.getName());
         if (record.isPresent()) {
            if (chb.getChecksum() != null && !chb.getChecksum().equals(record.get().getChecksum())) {
               throw new DatabaseMigrationException(String.format("Database %s has changeset %s with mismatched checksum. Please run migrations.", realm, chb.getName()));
            }
            if (record.get().getChangeSetVersion() < chb.getChangeSetVersion()) {
               throw new DatabaseMigrationException(String.format("Database %s has changeset %s with outdated version (recorded=%d, current=%d). Please run migrations.",
                       realm, chb.getName(), record.get().getChangeSetVersion(), chb.getChangeSetVersion()));
            }
         }
         // If no record exists, skip silently — the changeset may be new and not yet
         // applicable, or the DB was initialized before this changeset was added.
         // runAllUnRunMigrations() handles unrecorded changesets correctly.
      }

      if (currentSemVersion.compareTo(requiredSemVersion) > 0) {
         Log.warnf("Database %s version is higher than required. Current version: %s, required version: %s", realm, currentVersion.getCurrentVersionString(), targetDatabaseVersion);
      } else {
         Log.infof("Database %s version is up to date. Current version: %s, required version: %s — all changesets current", realm, currentVersion.getCurrentVersionString(), targetDatabaseVersion);
      }
   }

   public void dropIndexOnCollection (String realm, String collectionName) {
      mongoClient.getDatabase(realm).getCollection(collectionName).dropIndexes();
   }

   public void applyIndexes (String realmId) {
      Objects.requireNonNull(realmId, "RealmId cannot be null");
      DeclaredIndexes.ensureAll(morphiaDataStoreWrapper.getDataStore(realmId));
   }

   public void applyIndexes (String realmId, String collection) {
      Objects.requireNonNull(realmId, "RealmId cannot be null");
      Optional<EntityModel> em = morphiaDataStoreWrapper.getDataStore(realmId).getMapper().getMappedEntities().stream().filter(entity -> entity.collectionName().equals(collection)).findFirst();
      if (!em.isPresent()) {
         throw new NotFoundException(String.format("Collection %s not found in realm %s", collection, realmId));
      }
      DeclaredIndexes.ensure(morphiaDataStoreWrapper.getDataStore(realmId), em.get().getType());
   }

   /**
    * Applies every mapped entity's declared indexes in a realm, logging progress. See
    * {@link #applyAllIndexes(String, MultiEmitter)}.
    *
    * @param realmId the realm to apply indexes in
    * @throws IndexBuildException when any entity's indexes could not be applied
    */
   public void applyAllIndexes(String realmId) {
      applyAllIndexes(realmId, newLoggingEmitter());
   }

   /**
    * Applies every mapped entity's declared indexes in a realm. This is the single place declared
    * indexes are created; it runs at the end of every {@link #runAllUnRunMigrations} call.
    *
    * <p>Every entity is attempted. Failures are collected and thrown together as an
    * {@link IndexBuildException}, so a failed build fails the migration call instead of being
    * logged and skipped. Creating an index that already exists with the same name and definition
    * is a no-op, and this also creates any missing mapped collection. The step is additive: an
    * index that is no longer declared is reported as drift but never dropped; removals belong in
    * explicit migrations.</p>
    *
    * @param realmId the realm to apply indexes in
    * @param emitter receives progress and drift messages
    * @throws IndexBuildException when any entity's indexes could not be applied
    */
   public void applyAllIndexes(String realmId, MultiEmitter<? super String> emitter) {
      Objects.requireNonNull(realmId);
      MorphiaDatastore datastore = morphiaDataStoreWrapper.getDataStore(realmId);
      List<EntityModel> entities = new ArrayList<>(datastore.getMapper().getMappedEntities());
      log(String.format("-- Applying declared indexes for %d mapped entity types in realm %s --", entities.size(), realmId), emitter);

      List<String> failures = new ArrayList<>();
      Map<String, Set<String>> declaredByCollection = new TreeMap<>();
      IndexHelper indexHelper = new IndexHelper(datastore.getMapper());
      for (EntityModel entity : entities) {
         if (!DeclaredIndexes.isIndexable(entity)) {
            continue;
         }
         try {
            DeclaredIndexes.ensure(datastore, entity.getType());
            Log.debugf("applyAllIndexes: ensured indexes for %s in %s", entity.collectionName(), realmId);
         } catch (Exception e) {
            String failure = String.format("%s (%s): %s", entity.collectionName(), entity.getType().getSimpleName(), e.getMessage());
            Log.errorf(e, "applyAllIndexes: failed to ensure indexes for %s in %s", entity.collectionName(), realmId);
            log("!! Index build failed: " + failure, emitter);
            failures.add(failure);
         }
         try {
            declaredByCollection.computeIfAbsent(entity.collectionName(), name -> new TreeSet<>())
                  .addAll(DeclaredIndexes.names(indexHelper, entity));
         } catch (Exception e) {
            Log.debugf(e, "applyAllIndexes: could not compute declared indexes for %s", entity.collectionName());
         }
      }

      reportIndexDrift(datastore, declaredByCollection, emitter);

      if (!failures.isEmpty()) {
         throw new IndexBuildException(realmId, failures);
      }
      log(String.format("-- Declared indexes applied in realm %s --", realmId), emitter);
   }

   /**
    * Reports indexes that exist in a collection but are no longer declared by any entity mapped to
    * it (for example a replaced index). They are left in place; dropping one is a migration's job.
    */
   private void reportIndexDrift(MorphiaDatastore datastore, Map<String, Set<String>> declaredByCollection,
                                 MultiEmitter<? super String> emitter) {
      String realmId = datastore.getDatabase().getName();
      for (Map.Entry<String, Set<String>> collection : declaredByCollection.entrySet()) {
         try {
            for (Document index : datastore.getDatabase().getCollection(collection.getKey()).listIndexes()) {
               String name = index.getString("name");
               if (name != null && !"_id_".equals(name) && !collection.getValue().contains(name)) {
                  String message = String.format("Index drift in realm %s: collection %s has undeclared index %s %s",
                        realmId, collection.getKey(), name, index.get("key"));
                  Log.warn(message);
                  emitter.emit("   " + message);
               }
            }
         } catch (Exception e) {
            Log.debugf(e, "applyAllIndexes: could not list indexes for %s in %s", collection.getKey(), realmId);
         }
      }
   }

   public void dropAllIndexes (String realmId) {
      Objects.requireNonNull(realmId, "RealmId cannot be null");
      morphiaDataStoreWrapper.getDataStore(realmId).getMapper().getMappedEntities().forEach(entity -> {
         mongoClient.getDatabase(realmId).getCollection(entity.collectionName()).dropIndexes();
      });
   }


   public void checkMigrationRequired () {
      checkInitialized(defaultRealm);
      checkInitialized(systemRealm);
      checkInitialized(testRealm);
   }

   protected void log (String message, MultiEmitter<? super String> emitter) {
      Log.info(message);
      emitter.emit(message);
   }


   public void checkDataBaseVersion () {
      Log.info("MigrationService check is enabled");
      Log.infof("MigrationService targetDatabaseVersion: %s", targetDatabaseVersion);
      Log.infof("MigrationService databaseScope: %s", databaseScope);
      DistributedLock lock = getMigrationLock(systemRealm);
      lock.acquire();
      try {
         migrationRequired(morphiaDataStoreWrapper.getDataStore(defaultRealm), targetDatabaseVersion);
         migrationRequired(morphiaDataStoreWrapper.getDataStore(systemRealm), targetDatabaseVersion);
         migrationRequired(morphiaDataStoreWrapper.getDataStore(testRealm), targetDatabaseVersion);
      } finally {
         lock.release();
      }
   }

   protected DistributedLock getMigrationLock (String realm) {
      MongoCollection<Document> collection = mongoClient
                                                .getDatabase("sherlock")
                                                .getCollection("locks");
      Sherlock sherlock = MongoSherlock.create(collection);
      // create Lock name

      DistributedLock lock = sherlock.createLock(String.format("migration-lock-%s", realm));
      return lock;
   }

   public Optional<DatabaseVersion> getCurrentDatabaseVersion (String realm) {
      return getCurrentDatabaseVersion(morphiaDataStoreWrapper.getDataStore(realm));
   }

   public Optional<DatabaseVersion> getCurrentDatabaseVersion (Datastore datastore) {
      // Find the current version of the database.
      return databaseVersionRepo.findCurrentVersion(datastore);
   }

   public void migrationRequired (Datastore datastore, String requiredVersion) {

      Semver requiredSemver = Semver.parse(requiredVersion);
      if (requiredSemver == null) {
         throw new IllegalArgumentException(String.format(" realm: %s ,The current version string: %s is not parsable, check semver4j for more details about string format", datastore.getDatabase().getName(), requiredVersion));
      }

      Optional<DatabaseVersion> odbVersion = databaseVersionRepo.findCurrentVersion(datastore);
      if (odbVersion.isPresent()) {
         Log.infof("DBVersion: %s", odbVersion.get().toString());
         if (odbVersion.get().getCurrentSemVersion().isLowerThan(requiredVersion)) {
            throw new DatabaseMigrationException(datastore.getDatabase().getName(), odbVersion.get().toString(), requiredVersion);
         }

      } else
         throw new IllegalStateException(String.format("Empty database version collection found for  dataStore:%s, dataStore needs to be initialized", datastore.getDatabase().getName()));
   }

   public DatabaseVersion saveDatabaseVersion (Datastore datastore, String versionString) {
      if (versionString == null || versionString.isEmpty()) {
         throw new IllegalArgumentException("versionString cannot be null or empty");
      }


      Optional<DatabaseVersion> odbVersion = databaseVersionRepo.findByRefName(datastore, datastore.getDatabase().getName());
      DatabaseVersion dbVersion;

      if (odbVersion.isPresent()) {
         dbVersion = odbVersion.get();
         Log.infof("DBVersion: %s", dbVersion.toString());
         dbVersion.setCurrentVersionString(versionString);
         dbVersion.setLastUpdated(new java.util.Date());
      } else {
         dbVersion = new DatabaseVersion();
         dbVersion.setCurrentVersionString(versionString);
         dbVersion.setLastUpdated(new java.util.Date());
         dbVersion.setRefName(datastore.getDatabase().getName());
      }

      return databaseVersionRepo.save(datastore, dbVersion);
   }

   public List<ChangeSetBean> getAllChangeSetBeans () {
      if (Log.isDebugEnabled())
       Log.debug("== Finding changeSetBeans ===============");
      else {
         Log.info("Scanning for changeSet Beans - should only be seen on startup");
      }
      List<ChangeSetBean> changeSetBeans = new ArrayList<>();
      Set<Bean<?>> changeSets = beanManager.getBeans(ChangeSetBean.class);
      for (Bean bean : changeSets) {
         CreationalContext<?> creationalContext = beanManager.createCreationalContext(bean);
         ChangeSetBean chb = (ChangeSetBean) beanManager.getReference(bean, bean.getBeanClass(), creationalContext);
         if (Log.isDebugEnabled()) {}
            Log.debugf("Found ChangeSetBean: %s", chb.getName());
         changeSetBeans.add(chb);
      }

      // sort the change set beans in order of from / to and priority
      changeSetBeans.sort(Comparator.comparing(ChangeSetBean::getDbToVersionInt)
                             .thenComparing(ChangeSetBean::getPriority));

      return changeSetBeans;
   }

   public List<ChangeSetBean> getAllPendingChangeSetBeans (String realm, MultiEmitter<? super String> emitter) {
      Datastore datastore = morphiaDataStoreWrapper.getDataStore(realm);
      Optional<DatabaseVersion> oCurrentDbVersion = this.getCurrentDatabaseVersion(datastore);
      DatabaseVersion currentDbVersion;
      if (!oCurrentDbVersion.isPresent()) {
         log(String.format("No database version found in the database for realm: %s, assuming 1.0.0", realm), emitter);
         currentDbVersion = new DatabaseVersion();
         currentDbVersion.setCurrentVersionString("1.0.0");
         currentDbVersion = getDatabaseVersionRepo().save(realm, currentDbVersion);
      } else {
         currentDbVersion = oCurrentDbVersion.get();
         log(String.format("Current database version: %s", currentDbVersion.getCurrentVersionString()), emitter);
      }


      Semver currentSemDatabaseVersion = currentDbVersion.getCurrentSemVersion();
      List<ChangeSetBean> changeSets = getAllChangeSetBeans();
      log(String.format("Found %d Change Sets:", changeSets.size()), emitter);
      if (changeSets.isEmpty()) {
         log("No Change Sets found", emitter);
      }
      log(String.format("Found %d Change Sets:", changeSets.size()), emitter);
      changeSets.forEach(changeSetBean -> {
         log(String.format("    Change Set: %s", changeSetBean.getName()), emitter);
      });
      List<ChangeSetBean> pendingChangeSetBeans = new ArrayList<>();

      for (ChangeSetBean chb : changeSets) {
         Semver toVersion = new Semver(chb.getDbToVersion());
         Semver currentVersion = new Semver(currentSemDatabaseVersion.getVersion());

         Optional<ChangeSetRecord> record = changesetRecordRepo.findLatestByChangeSetName(datastore, chb.getName());
         boolean shouldRun = !record.isPresent() ||
                             record.get().getChangeSetVersion() < chb.getChangeSetVersion() ||
                             (chb.getChecksum() != null && !chb.getChecksum().equals(record.get().getChecksum()));

         if (toVersion.isGreaterThanOrEqualTo(currentVersion)) {
            log(String.format("ToVersion:%s, is greater than or equal to current version:%s", toVersion.getVersion(), currentVersion.getVersion()), emitter);
            if (shouldRun) {
               String reason = !record.isPresent() ? "no record" :
                              (record.get().getChangeSetVersion() < chb.getChangeSetVersion() ? "version changed" : "checksum changed");
               log(String.format(">> Adding Change Set: %s (v%d) in realm %s - reason: %s", chb.getName(), chb.getChangeSetVersion(), datastore.getDatabase().getName(), reason), emitter);
               pendingChangeSetBeans.add(chb);
            } else {
               log(String.format(">> Already executed change set: %s (latest applied v%d) in realm %s on %tc ", chb.getName(), record.get().getChangeSetVersion(), datastore.getDatabase().getName(), record.get().getLastExecutedDate()), emitter);
            }
         } else if (shouldRun) {
            log(String.format(">> Adding Change Set: %s (v%d) in realm %s - reason: checksum changed (older version) or change record not found", chb.getName(), chb.getChangeSetVersion(), datastore.getDatabase().getName()), emitter);
            pendingChangeSetBeans.add(chb);
         } else {
            log(String.format(">> Skipping Change Set:%s (already applied, toVersion:%s < currentVersion:%s)", chb.getName(), toVersion.getVersion(), currentSemDatabaseVersion), emitter);
         }
      }
      return pendingChangeSetBeans;
   }

   public DatabaseVersionRepo getDatabaseVersionRepo () {
      return databaseVersionRepo;
   }


   public void updateChangeLog (MorphiaSession ds, String realm, ChangeSetBean changeSetBean, MultiEmitter<? super String> emitter) {
      // Upsert ChangeSetRecord by changeSetName to avoid duplicate-key on older deployments with a unique index on name only
      Optional<ChangeSetRecord> existing = changesetRecordRepo.findLatestByChangeSetName(ds, changeSetBean.getName());
      ChangeSetRecord record;
      if (existing.isPresent()) {
         record = existing.get();
      } else {
         record = new ChangeSetRecord();
      }
      // Populate/overwrite all fields
      record.setRealm(realm);
      record.setRefName(changeSetBean.getName());
      record.setAuthor(changeSetBean.getAuthor());
      record.setChangeSetName(changeSetBean.getName());
      record.setDescription(changeSetBean.getDescription());
      record.setPriority(changeSetBean.getPriority());
      record.setDbFromVersion(changeSetBean.getDbFromVersion());
      record.setDbFromVersionInt(changeSetBean.getDbFromVersionInt());
      record.setDbToVersion(changeSetBean.getDbToVersion());
      record.setDbToVersionInt(changeSetBean.getDbToVersionInt());
      record.setChangeSetVersion(changeSetBean.getChangeSetVersion());
      record.setChecksum(changeSetBean.getChecksum());
      record.setLastExecutedDate(new Date());
      record.setScope(changeSetBean.getScope());
      record.setSuccessful(true);

      changesetRecordRepo.save(ds, record);

      DatabaseVersion databaseVersion;
      Optional<DatabaseVersion> oversion = databaseVersionRepo.findCurrentVersion(ds);
      if (!oversion.isPresent()) {
         Log.warnf("        No database databaseVersion found in the database for realm: %s, assuming 1.0.0", realm);
         emitter.emit(String.format("        No database databaseVersion found in the database for realm: %s, assuming 1.0.0", realm));
         databaseVersion = new DatabaseVersion();
         databaseVersion.setRefName(realm);
         databaseVersion.setCurrentVersionString(record.getDbToVersion());
         databaseVersion = databaseVersionRepo.save(ds, databaseVersion);
      } else {
         databaseVersion = oversion.get();
         if (databaseVersion.getCurrentSemVersion().isLowerThan(record.getDbToVersion())) {
            databaseVersion.setCurrentVersionString(record.getDbToVersion());
            databaseVersion = databaseVersionRepo.save(ds, databaseVersion);
         }
      }
   }

   public void runChangeSetBean(String changeSetName, String realm, MultiEmitter<? super String> emitter) throws Exception {

      List<ChangeSetBean> changeSets = getAllChangeSetBeans();
      Optional<ChangeSetBean> changeSetBeanOptional = changeSets.stream().filter(changeSetBean -> changeSetBean.getName().equals(changeSetName)).findFirst();
      if (!changeSetBeanOptional.isPresent()) {
         throw new NotFoundException(String.format("Change Set Bean:%s not found", changeSetName));
      }
      ChangeSetBean changeSetBean = changeSetBeanOptional.get();

      // get a lock first:
      DistributedLock lock = getMigrationLock(realm);
      if (!lock.acquire(migrationLockDuration)) {
         throw new MigrationInProgressException(realm);
      }
      log(String.format("-- Got Migration Lock Executing change sets on database / realm:%s --", realm), emitter);
      try {
         MorphiaSession ds = morphiaDataStoreWrapper.getDataStore(realm).startSession();
         try {
            log(String.format("        Executing Change Set: %s in realm %s", changeSetBean.getName(), realm), emitter);
            emitter.emit(String.format("        Executing Change Set: %s in realm %s", changeSetBean.getName(), realm));
            try {
               changeSetBean.execute(ds, mongoClient, emitter);
            } catch (Exception e) {
               throw new RuntimeException(e);
            }
            log(String.format("        Executed Change Set: %s in realm %s", changeSetBean.getName(), realm), emitter);
            emitter.emit(String.format("        Executed Change Set: %s in realm %s", changeSetBean.getName(), realm));
            updateChangeLog(ds, realm, changeSetBean, emitter);
         } finally {
            ds.close();
         }
      } finally {
         lock.release();
      }
   }

   /**
    * Migrates a realm: runs its pending change sets, then applies every declared index
    * ({@link #applyAllIndexes(String, MultiEmitter)}). The index step runs on every call, including
    * when no change sets are pending, so an index added only to a model annotation reaches realms
    * that are already up to date.
    *
    * <p>The whole call holds the realm's migration lock for {@code quantum.database.migration.lock-duration}.
    * If another call holds it, this call fails with {@link MigrationInProgressException} instead of
    * silently doing nothing.</p>
    *
    * @param realm the realm (database) to migrate
    * @param emitter receives progress messages
    * @throws MigrationInProgressException when the realm is already being migrated
    * @throws IndexBuildException when declared indexes could not all be applied; each change set
    *                             has already recorded its database version, and the next call
    *                             retries the index step
    */
   public void runAllUnRunMigrations (String realm, MultiEmitter<? super String> emitter) {

      Log.info("-- Running all migrations for realm: " + realm);
      DistributedLock lock = getMigrationLock(realm);
      if (!lock.acquire(migrationLockDuration)) {
         throw new MigrationInProgressException(realm);
      }
      log(String.format("-- Got Migration Lock on database / realm:%s --", realm), emitter);
      try {
         runPendingChangeSets(realm, getAllPendingChangeSetBeans(realm, emitter), emitter);
         applyAllIndexes(realm, emitter);
      } finally {
         lock.release();
         log("-- Lock Released --", emitter);
      }
   }

   private void runPendingChangeSets(String realm, List<ChangeSetBean> changeSetList, MultiEmitter<? super String> emitter) {
      if (!changeSetList.isEmpty()) {
         Log.infof("-------------- Migration Starting for: %s--------------", realm);
         emitter.emit(String.format("-------------- Migration Starting for: %s--------------", realm));
         // for each now execute the change set bean
         Log.infof("-- Executing %d change sets --", changeSetList.size());
         emitter.emit(String.format("-- Executing %d change sets --", changeSetList.size()));
         {
            changeSetList.forEach(changeSetBean -> {
               log(String.format("    checking for previous execution of Change Set: %s, in database %s", changeSetBean.getName(), realm ), emitter);
               // first check if this change set has run already or not
               Optional<ChangeSetRecord> changeSetRec = changesetRecordRepo.findLatestByChangeSetName(morphiaDataStoreWrapper.getDataStore(realm), changeSetBean.getName());
               boolean shouldRun = !changeSetRec.isPresent() ||
                                   changeSetRec.get().getChangeSetVersion() < changeSetBean.getChangeSetVersion() ||
                                   (changeSetBean.getChecksum() != null && !changeSetBean.getChecksum().equals(changeSetRec.get().getChecksum()));
               if (changeSetRec.isPresent()) {
                  log(String.format("    Found existing record for %s: version=%d, checksum=%s; Bean: version=%d, checksum=%s",
                     changeSetBean.getName(), changeSetRec.get().getChangeSetVersion(), changeSetRec.get().getChecksum(),
                     changeSetBean.getChangeSetVersion(), changeSetBean.getChecksum()), emitter);
               }
               if (shouldRun) {

                  if ((changeSetBean.getApplicableDatabases() == null) ||
                         (changeSetBean.getApplicableDatabases() != null && changeSetBean.getApplicableDatabases().contains(realm))
                  ) {
                     Log.infof("Executing Change Set:%s", changeSetBean.getName());
                     emitter.emit(String.format("Executing Change Set:%s", changeSetBean.getName()));

                     if (changeSetBean.isOverrideDatabase() && changeSetBean.getOverrideDatabaseName() != null &&
                            !changeSetBean.getOverrideDatabaseName().isEmpty() && !changeSetBean.getOverrideDatabaseName().equalsIgnoreCase(realm)) {
                        log(String.format("Overridden Database for changeSetBean:%s, to database:%s from default:%s", changeSetBean.getName(), changeSetBean.getOverrideDatabaseName(), realm), emitter);
                        MorphiaSession ods = morphiaDataStoreWrapper.getDataStore(changeSetBean.getOverrideDatabaseName()).startSession();

                        DistributedLock olock = getMigrationLock(changeSetBean.getOverrideDatabaseName());
                        if (!olock.acquire(migrationLockDuration)) {
                           ods.close();
                           throw new MigrationInProgressException(changeSetBean.getOverrideDatabaseName());
                        }
                        {
                           try {
                              Log.infof("        Starting Transaction for Change Set:%s on database: %s", changeSetBean.getName(), changeSetBean.getOverrideDatabaseName());
                              emitter.emit(String.format("        Starting Transaction for Change Set:%s", changeSetBean.getName()));

                              ods.startTransaction();
                              changeSetBean.execute(ods, mongoClient, emitter);
                              updateChangeLog(ods, changeSetBean.getOverrideDatabaseName(), changeSetBean, emitter);
                              ods.commitTransaction();
                              Log.infof("        Committed Transaction for Change Set:%s", changeSetBean.getName());
                              emitter.emit(String.format("        Commited Transaction for Change Set:%s", changeSetBean.getName()));
                           } catch (Throwable e) {
                              emitter.fail(e);
                              e.printStackTrace();
                              ods.abortTransaction();
                              throw new RuntimeException(e);
                           } finally {
                              ods.close();
                              olock.release();
                           }
                        }
                     } else {
                        MorphiaSession ds = morphiaDataStoreWrapper.getDataStore(realm).startSession();
                        try {
                           log(String.format("        Starting Transaction for Change Set:%s on database", changeSetBean.getName(), realm), emitter);

                           ds.startTransaction();
                           changeSetBean.execute(ds, mongoClient, emitter);
                           updateChangeLog(ds, realm, changeSetBean, emitter);
                           ds.commitTransaction();
                           Log.infof("        Committed Transaction for Change Set:%s", changeSetBean.getName());
                           emitter.emit(String.format("        Commited Transaction for Change Set:%s", changeSetBean.getName()));
                        } catch (Throwable e) {
                           emitter.fail(e);
                           e.printStackTrace();
                           ds.abortTransaction();
                           throw new RuntimeException(e);
                        } finally {
                           ds.close();
                        }
                     }
                  } else {
                     log(String.format("Ignoring Change Set:%s because it is not applicable to realm:%s", changeSetBean.getName(), realm), emitter);
                  }
               } else {
                  Log.infof("Change Set:%s has already been executed on database %s", changeSetBean.getName(), realm);
               }
               });
               log("-- All Change Sets executed --", emitter);
               log(String.format("-------------- Migration Completed for: %s--------------", realm), emitter);
            }
         } else {
            log(String.format("-- No pending change sets found for realm: %s", realm), emitter);
         }
   }



    private static @NotNull ChangeSetRecord newChangeSetRecord(String realm, ChangeSetBean changeSetBean) {
        ChangeSetRecord record = new ChangeSetRecord();
        record.setRealm(realm);
        record.setRefName(changeSetBean.getName());
        record.setAuthor(changeSetBean.getAuthor());
        record.setChangeSetName(changeSetBean.getName());
        record.setDescription(changeSetBean.getDescription());
        record.setPriority(changeSetBean.getPriority());
        record.setDbFromVersion(changeSetBean.getDbFromVersion());
        record.setDbFromVersionInt(changeSetBean.getDbFromVersionInt());
        record.setDbToVersion(changeSetBean.getDbToVersion());
        record.setDbToVersionInt(changeSetBean.getDbToVersionInt());
        record.setChangeSetVersion(changeSetBean.getChangeSetVersion());
        record.setLastExecutedDate(new Date());
        record.setScope(changeSetBean.getScope());
        record.setSuccessful(true);
        return record;
    }

}
