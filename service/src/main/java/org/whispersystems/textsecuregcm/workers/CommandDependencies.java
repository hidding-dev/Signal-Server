/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.workers;

import static com.codahale.metrics.MetricRegistry.name;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.dropwizard.setup.Environment;
import io.lettuce.core.resource.ClientResources;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.whispersystems.textsecuregcm.WhisperServerConfiguration;
import org.whispersystems.textsecuregcm.WhisperServerService;
import org.whispersystems.textsecuregcm.auth.ExternalServiceCredentialsGenerator;
import org.whispersystems.textsecuregcm.configuration.dynamic.DynamicConfiguration;
import org.whispersystems.textsecuregcm.controllers.SecureBackupController;
import org.whispersystems.textsecuregcm.controllers.SecureStorageController;
import org.whispersystems.textsecuregcm.controllers.SecureValueRecovery2Controller;
import org.whispersystems.textsecuregcm.experiment.ExperimentEnrollmentManager;
import org.whispersystems.textsecuregcm.push.ClientPresenceManager;
import org.whispersystems.textsecuregcm.redis.FaultTolerantRedisCluster;
import org.whispersystems.textsecuregcm.securebackup.SecureBackupClient;
import org.whispersystems.textsecuregcm.securestorage.SecureStorageClient;
import org.whispersystems.textsecuregcm.securevaluerecovery.SecureValueRecovery2Client;
import org.whispersystems.textsecuregcm.storage.AccountLockManager;
import org.whispersystems.textsecuregcm.storage.Accounts;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import org.whispersystems.textsecuregcm.storage.DeletedAccounts;
import org.whispersystems.textsecuregcm.storage.DynamicConfigurationManager;
import org.whispersystems.textsecuregcm.storage.KeysManager;
import org.whispersystems.textsecuregcm.storage.MessagesCache;
import org.whispersystems.textsecuregcm.storage.MessagesDynamoDb;
import org.whispersystems.textsecuregcm.storage.MessagesManager;
import org.whispersystems.textsecuregcm.storage.PhoneNumberIdentifiers;
import org.whispersystems.textsecuregcm.storage.Profiles;
import org.whispersystems.textsecuregcm.storage.ProfilesManager;
import org.whispersystems.textsecuregcm.storage.RegistrationRecoveryPasswords;
import org.whispersystems.textsecuregcm.storage.RegistrationRecoveryPasswordsManager;
import org.whispersystems.textsecuregcm.storage.ReportMessageDynamoDb;
import org.whispersystems.textsecuregcm.storage.ReportMessageManager;
import org.whispersystems.textsecuregcm.storage.StoredVerificationCodeManager;
import org.whispersystems.textsecuregcm.storage.VerificationCodeStore;
import org.whispersystems.textsecuregcm.util.DynamoDbFromConfig;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Construct utilities commonly used by worker commands
 */
record CommandDependencies(
    AccountsManager accountsManager,
    ProfilesManager profilesManager,
    ReportMessageManager reportMessageManager,
    MessagesCache messagesCache,
    MessagesManager messagesManager,
    StoredVerificationCodeManager pendingAccountsManager,
    ClientPresenceManager clientPresenceManager,
    KeysManager keysManager,
    FaultTolerantRedisCluster cacheCluster,
    ClientResources redisClusterClientResources) {

  static CommandDependencies build(
      final String name,
      final Environment environment,
      final WhisperServerConfiguration configuration) throws IOException, CertificateException {
    Clock clock = Clock.systemUTC();

    environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    ClientResources redisClusterClientResources = ClientResources.builder().build();

    FaultTolerantRedisCluster cacheCluster = new FaultTolerantRedisCluster("main_cache_cluster",
        configuration.getCacheClusterConfiguration(), redisClusterClientResources);

    Scheduler messageDeliveryScheduler = Schedulers.fromExecutorService(
        environment.lifecycle().executorService("messageDelivery").maxThreads(4)
            .build());
    ExecutorService keyspaceNotificationDispatchExecutor = environment.lifecycle()
        .executorService(name(name, "keyspaceNotification-%d")).maxThreads(4).build();
    ExecutorService messageDeletionExecutor = environment.lifecycle()
        .executorService(name(name, "messageDeletion-%d")).maxThreads(4).build();
    ExecutorService secureValueRecoveryServiceExecutor = environment.lifecycle()
        .executorService(name(name, "secureValueRecoveryService-%d")).maxThreads(8).minThreads(8).build();
    ExecutorService storageServiceExecutor = environment.lifecycle()
        .executorService(name(name, "storageService-%d")).maxThreads(8).minThreads(8).build();

    ExternalServiceCredentialsGenerator backupCredentialsGenerator = SecureBackupController.credentialsGenerator(
        configuration.getSecureBackupServiceConfiguration());
    ExternalServiceCredentialsGenerator storageCredentialsGenerator = SecureStorageController.credentialsGenerator(
        configuration.getSecureStorageServiceConfiguration());
    ExternalServiceCredentialsGenerator secureValueRecoveryCredentialsGenerator = SecureValueRecovery2Controller.credentialsGenerator(
        configuration.getSvr2Configuration());

    DynamicConfigurationManager<DynamicConfiguration> dynamicConfigurationManager = new DynamicConfigurationManager<>(
        configuration.getAppConfig().getApplication(), configuration.getAppConfig().getEnvironment(),
        configuration.getAppConfig().getConfigurationName(), DynamicConfiguration.class);
    dynamicConfigurationManager.start();

    ExperimentEnrollmentManager experimentEnrollmentManager = new ExperimentEnrollmentManager(
        dynamicConfigurationManager);

    DynamoDbAsyncClient dynamoDbAsyncClient = DynamoDbFromConfig.asyncClient(
        configuration.getDynamoDbClientConfiguration(), WhisperServerService.AWSSDK_CREDENTIALS_PROVIDER);

    DynamoDbClient dynamoDbClient = DynamoDbFromConfig.client(
        configuration.getDynamoDbClientConfiguration(), WhisperServerService.AWSSDK_CREDENTIALS_PROVIDER);

    AmazonDynamoDB deletedAccountsLockDynamoDbClient = AmazonDynamoDBClientBuilder.standard()
        .withRegion(configuration.getDynamoDbClientConfiguration().getRegion())
        .withClientConfiguration(new ClientConfiguration().withClientExecutionTimeout(
                ((int) configuration.getDynamoDbClientConfiguration().getClientExecutionTimeout()
                    .toMillis()))
            .withRequestTimeout(
                (int) configuration.getDynamoDbClientConfiguration().getClientRequestTimeout()
                    .toMillis()))
        .withCredentials(WhisperServerService.AWSSDK_V1_CREDENTIALS_PROVIDER_CHAIN)
        .build();

    DeletedAccounts deletedAccounts = new DeletedAccounts(dynamoDbClient,
        configuration.getDynamoDbTables().getDeletedAccounts().getTableName());
    VerificationCodeStore pendingAccounts = new VerificationCodeStore(dynamoDbClient,
        configuration.getDynamoDbTables().getPendingAccounts().getTableName());
    RegistrationRecoveryPasswords registrationRecoveryPasswords = new RegistrationRecoveryPasswords(
        configuration.getDynamoDbTables().getRegistrationRecovery().getTableName(),
        configuration.getDynamoDbTables().getRegistrationRecovery().getExpiration(),
        dynamoDbClient,
        dynamoDbAsyncClient
    );

    RegistrationRecoveryPasswordsManager registrationRecoveryPasswordsManager = new RegistrationRecoveryPasswordsManager(
        registrationRecoveryPasswords);

    Accounts accounts = new Accounts(
        dynamoDbClient,
        dynamoDbAsyncClient,
        configuration.getDynamoDbTables().getAccounts().getTableName(),
        configuration.getDynamoDbTables().getAccounts().getPhoneNumberTableName(),
        configuration.getDynamoDbTables().getAccounts().getPhoneNumberIdentifierTableName(),
        configuration.getDynamoDbTables().getAccounts().getUsernamesTableName(),
        configuration.getDynamoDbTables().getAccounts().getScanPageSize());
    PhoneNumberIdentifiers phoneNumberIdentifiers = new PhoneNumberIdentifiers(dynamoDbClient,
        configuration.getDynamoDbTables().getPhoneNumberIdentifiers().getTableName());
    Profiles profiles = new Profiles(dynamoDbClient, dynamoDbAsyncClient,
        configuration.getDynamoDbTables().getProfiles().getTableName());
    KeysManager keys = new KeysManager(
            dynamoDbAsyncClient,
        configuration.getDynamoDbTables().getEcKeys().getTableName(),
        configuration.getDynamoDbTables().getKemKeys().getTableName(),
        configuration.getDynamoDbTables().getKemLastResortKeys().getTableName());
    MessagesDynamoDb messagesDynamoDb = new MessagesDynamoDb(dynamoDbClient, dynamoDbAsyncClient,
        configuration.getDynamoDbTables().getMessages().getTableName(),
        configuration.getDynamoDbTables().getMessages().getExpiration(),
        messageDeletionExecutor);
    FaultTolerantRedisCluster messageInsertCacheCluster = new FaultTolerantRedisCluster("message_insert_cluster",
        configuration.getMessageCacheConfiguration().getRedisClusterConfiguration(), redisClusterClientResources);
    FaultTolerantRedisCluster messageReadDeleteCluster = new FaultTolerantRedisCluster("message_read_delete_cluster",
        configuration.getMessageCacheConfiguration().getRedisClusterConfiguration(), redisClusterClientResources);
    FaultTolerantRedisCluster clientPresenceCluster = new FaultTolerantRedisCluster("client_presence_cluster",
        configuration.getClientPresenceClusterConfiguration(), redisClusterClientResources);
    FaultTolerantRedisCluster rateLimitersCluster = new FaultTolerantRedisCluster("rate_limiters",
        configuration.getRateLimitersCluster(), redisClusterClientResources);
    SecureBackupClient secureBackupClient = new SecureBackupClient(backupCredentialsGenerator,
        secureValueRecoveryServiceExecutor,
        configuration.getSecureBackupServiceConfiguration());
    SecureValueRecovery2Client secureValueRecovery2Client = new SecureValueRecovery2Client(
        secureValueRecoveryCredentialsGenerator, secureValueRecoveryServiceExecutor,
        configuration.getSvr2Configuration());
    SecureStorageClient secureStorageClient = new SecureStorageClient(storageCredentialsGenerator,
        storageServiceExecutor, configuration.getSecureStorageServiceConfiguration());
    ClientPresenceManager clientPresenceManager = new ClientPresenceManager(clientPresenceCluster,
        Executors.newSingleThreadScheduledExecutor(), keyspaceNotificationDispatchExecutor);
    MessagesCache messagesCache = new MessagesCache(messageInsertCacheCluster, messageReadDeleteCluster,
        keyspaceNotificationDispatchExecutor, messageDeliveryScheduler, messageDeletionExecutor, Clock.systemUTC());
    ProfilesManager profilesManager = new ProfilesManager(profiles, cacheCluster);
    ReportMessageDynamoDb reportMessageDynamoDb = new ReportMessageDynamoDb(dynamoDbClient,
        configuration.getDynamoDbTables().getReportMessage().getTableName(),
        configuration.getReportMessageConfiguration().getReportTtl());
    ReportMessageManager reportMessageManager = new ReportMessageManager(reportMessageDynamoDb, rateLimitersCluster,
        configuration.getReportMessageConfiguration().getCounterTtl());
    MessagesManager messagesManager = new MessagesManager(messagesDynamoDb, messagesCache,
        reportMessageManager, messageDeletionExecutor);
    AccountLockManager accountLockManager = new AccountLockManager(deletedAccountsLockDynamoDbClient, configuration.getDynamoDbTables().getDeletedAccountsLock().getTableName());
    StoredVerificationCodeManager pendingAccountsManager = new StoredVerificationCodeManager(pendingAccounts);
    AccountsManager accountsManager = new AccountsManager(accounts, phoneNumberIdentifiers, cacheCluster,
        accountLockManager, deletedAccounts, keys, messagesManager, profilesManager,
        pendingAccountsManager, secureStorageClient, secureBackupClient, secureValueRecovery2Client, clientPresenceManager,
        experimentEnrollmentManager, registrationRecoveryPasswordsManager, clock);

    return new CommandDependencies(
        accountsManager,
        profilesManager,
        reportMessageManager,
        messagesCache,
        messagesManager,
        pendingAccountsManager,
        clientPresenceManager,
        keys,
        cacheCluster,
        redisClusterClientResources
    );
  }

}
