/*
 * Copyright (c) 2014 Couchbase, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.couchbase.client.jcache;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.configuration.Configuration;
import javax.cache.event.EventType;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheWriter;
import javax.cache.integration.CompletionListener;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.EntryProcessorException;
import javax.cache.processor.EntryProcessorResult;

import com.couchbase.client.core.lang.Tuple;
import com.couchbase.client.core.lang.Tuple2;
import com.couchbase.client.core.lang.Tuple3;
import com.couchbase.client.core.logging.CouchbaseLogger;
import com.couchbase.client.core.logging.CouchbaseLoggerFactory;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.document.SerializableDocument;
import com.couchbase.client.java.error.CASMismatchException;
import com.couchbase.client.java.error.DocumentAlreadyExistsException;
import com.couchbase.client.java.error.DocumentDoesNotExistException;
import com.couchbase.client.java.view.AsyncViewResult;
import com.couchbase.client.java.view.AsyncViewRow;
import com.couchbase.client.java.view.DesignDocument;
import com.couchbase.client.java.view.View;
import com.couchbase.client.java.view.ViewQuery;
import com.couchbase.client.jcache.management.CouchbaseCacheMxBean;
import com.couchbase.client.jcache.management.CouchbaseStatisticsMxBean;
import com.couchbase.client.jcache.management.ManagementUtil;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Actions;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

/**
 * The Couchbase implementation of a @{link Cache}. Note that type V must be {@link Serializable}!
 *
 * @author Simon Baslé
 * @since 1.0
 */
public class CouchbaseCache<K, V> implements Cache<K, V> {

    private static final CouchbaseLogger LOGGER = CouchbaseLoggerFactory.getInstance(CouchbaseCache.class);

    /** 30 days in seconds */
    private static final long MAX_TTL = 30 * 24 * 60 * 60;
    private static final int TTL_DONT_CHANGE = -2;
    private static final int TTL_EXPIRED = -1;
    private static final int TTL_NONE = 0;

    private final CouchbaseCacheManager cacheManager;
    private final String name;

    private final ExpiryPolicy expiryPolicy;
    private final CouchbaseConfiguration<K, V> configuration;
    private final CacheLoader<K, V> cacheLoader;
    private final CacheWriter<? super K, ? super V> cacheWriter;
    private final CouchbaseCacheMxBean cacheMxBean;
    private final CouchbaseStatisticsMxBean statisticsMxBean;
    private final CacheEventManager<K, V> eventManager;
    private final KeyConverter<K> keyConverter;

    private volatile boolean isClosed;

    /*package scope*/
    final Bucket bucket;

    /* package scope*/
    <T extends CouchbaseCacheManager> CouchbaseCache(T cacheManager, CouchbaseConfiguration<K, V> conf) {
        this.cacheManager = cacheManager;
        if (conf.getCacheName() == null) {
            throw new NullPointerException("Cache name in configuration cannot be null");
        }
        this.name = conf.getCacheName();
        //make a local copy of the configuration for this cache
        this.configuration = new CouchbaseConfiguration<K, V>(conf);

        if (this.configuration.getCacheLoaderFactory() != null) {
            this.cacheLoader = this.configuration.getCacheLoaderFactory().create();
        } else {
            this.cacheLoader = null;
        }
        if (this.configuration.getCacheWriterFactory() != null) {
            this.cacheWriter = this.configuration.getCacheWriterFactory().create();
        } else {
            this.cacheWriter = null;
        }

        this.expiryPolicy = this.configuration.getExpiryPolicyFactory().create();

        this.cacheMxBean = new CouchbaseCacheMxBean(this);
        this.statisticsMxBean = new CouchbaseStatisticsMxBean();

        this.isClosed = false;

        if (configuration.isManagementEnabled()) {
            setManagementEnabled(true);
        }
        if (configuration.isStatisticsEnabled()) {
            setStatisticsEnabled(true);
        }

        this.eventManager = new CacheEventManager<K, V>();
        for (CacheEntryListenerConfiguration<K, V> config : configuration.getCacheEntryListenerConfigurations()) {
            this.eventManager.addListener(config);
        }

        String keyPrefix = configuration.getCachePrefix() == null ? "" : configuration.getCachePrefix();
        this.keyConverter = configuration.getCachePrefix() == null
                ? configuration.getKeyConverter()
                : new KeyConverter.PrefixedKeyConverter<K>(configuration.getKeyConverter(), keyPrefix);
        this.bucket = cacheManager.getCluster().openBucket(configuration.getBucketName(),
                configuration.getBucketPassword());
    }

    public KeyConverter<K> keyConverter() {
        return this.keyConverter;
    }

    /**
     * Allows to enable/disable statistics via JMX.
     * This will also update the configuration.
     *
     * @param enabled the new status of statistics
     */
    private void setStatisticsEnabled(boolean enabled) {
        if (enabled) {
            ManagementUtil.registerStatistics(this, this.statisticsMxBean);
        } else {
            ManagementUtil.unregisterStatistics(this);
        }
        this.configuration.setStatisticsEnabled(enabled);
    }

    /**
     * Checks the status of statistics.
     *
     * @return true if statistics are enabled in configuration, false otherwise
     */
    private boolean isStatisticsEnabled() {
        return configuration.isStatisticsEnabled();
    }

    /**
     * Allows to enable/disable management via JMX.
     * This will also update the configuration.
     *
     * @param enabled the enabled flag value
     */
    private void setManagementEnabled(boolean enabled) {
        if (enabled) {
            ManagementUtil.registerConfiguration(this, this.cacheMxBean);
        } else {
            ManagementUtil.unregisterConfiguration(this);
        }
        this.configuration.setManagementEnabled(enabled);
    }

    /**
     * Checks the status of management.
     *
     * @return true if management is enabled in configuration, false otherwise
     */
    private boolean isManagementEnabled() {
        return configuration.isManagementEnabled();
    }

    @Override
    public V get(K key) {
        //TODO expiry
        checkOpen();
        long start = (isStatisticsEnabled()) ? System.nanoTime() : 0;
        String cbKey = toInternalKey(key);
        V result = null;

        SerializableDocument doc = bucket.get(cbKey, SerializableDocument.class);
        try {
            if (doc != null) {
                //when an entry is found, just update its expiry if ACCESS warrants it
                if (isStatisticsEnabled()) {
                    statisticsMxBean.increaseCacheHits(1L);
                }
                touchIfNeeded(cbKey);
                result = (V) doc.content();
            } else {
                //no entry found, still try to apply read-through
                if (isStatisticsEnabled()) {
                    statisticsMxBean.increaseCacheMisses(1L);
                }
                if (cacheLoader != null && configuration.isReadThrough()) {
                    V loaded = cacheLoader.load(key);
                    if (loaded != null && (doc = createDocument(key, loaded, Operation.CREATION)) != null) {
                        try {
                            bucket.insert(doc);
                            result = loaded;
                            //a successful read-through triggers a CREATED notification
                            eventManager.queueAndDispatch(EventType.CREATED, key, loaded, this);
                        } catch (DocumentAlreadyExistsException e) {
                            //concurrent creation of document succeeded, abandon loading
                        }
                    }
                }
            }
            if (isStatisticsEnabled()) {
                statisticsMxBean.addGetTimeNano(System.nanoTime() - start);
            }
            return result;
        } catch (Exception e) {
            throw new CacheException("Get " + key + " failed", e);
        }
    }

    @Override
    public Map<K, V> getAll(Set<? extends K> keys) {
        checkOpen();
        Map<K, V> result = new HashMap<K, V>(keys.size());
        for (K key : keys) {
            //TODO optimize for bulk loads?
            V value = get(key);
            if (value != null) {
                result.put(key, value);
            }
        }

        return result;
    }

    /**
     * {@inheritDoc}
     *
     * Note that this implementation attempts a load of the document from couchbase.
     * It is more efficient to directly attempt to retrieve the value with get than call containsKey then get in this
     * cache implementation, unless you don't want to trigger statistics and/or read-through (if activated).
     *
     * @param key the key to check for
     * @return true if key is present in cache, false otherwise
     */
    @Override
    public boolean containsKey(K key) {
        checkOpen();
        return bucket.get(toInternalKey(key), SerializableDocument.class) != null;
    }

    @Override
    public void loadAll(final Set<? extends K> keys, final boolean replaceExistingValues, final CompletionListener completionListener) {
        checkOpen();

        if (keys == null) {
            throw new NullPointerException("Keys cannot be null");
        }
        for (K key : keys) {
            if (key == null) {
                throw new NullPointerException("Keys cannot include a null key");
            }
        }

        if (cacheLoader == null) {
            if (completionListener != null) {
                completionListener.onCompletion();
            }
            return;
        }

        Observable.just(keys)
                //operate in an IO thread
                .subscribeOn(Schedulers.io())
                //load all data from CacheLoader
                .map(new Func1<Set<? extends K>, Map<K, V>>() {
                    @Override
                    public Map<K, V> call(Set<? extends K> ks) {
                        return cacheLoader.loadAll(ks);
                    }
                })
                //work on the actually loaded data
                .flatMap(new Func1<Map<K, V>, Observable<Map.Entry<K, V>>>() {
                    @Override
                    public Observable<Map.Entry<K, V>> call(Map<K, V> kvMap) {
                        return Observable.from(kvMap.entrySet());
                    }
                })
                //attempt a get to see if data is already in cache, keep the key, value and current value as tuple
                .flatMap(new Func1<Map.Entry<K, V>, Observable<Tuple3<K, V, SerializableDocument>>>() {
                    @Override
                    public Observable<Tuple3<K, V, SerializableDocument>> call(final Map.Entry<K, V> kv) {
                        return bucket.async().get(toInternalKey(kv.getKey()), SerializableDocument.class)
                        .map(new Func1<SerializableDocument, Tuple3<K, V, SerializableDocument>>() {
                            @Override
                            public Tuple3<K, V, SerializableDocument> call(SerializableDocument doc) {
                                return Tuple.create(kv.getKey(), kv.getValue(), doc);
                            }
                        });
                    }
                })
                //depending on if the value is already in cache or not, insert or replace. Keep track in tuple.
                .flatMap(new Func1<Tuple3<K, V, SerializableDocument>, Observable<Tuple3<K, V, V>>>() {
                    @Override
                    public Observable<Tuple3<K, V, V>> call(final Tuple3<K, V, SerializableDocument> kvd) {
                        if (kvd.value3() == null) {
                            //no value in cache. take expiry into account to see if a creation is needed.
                            SerializableDocument newDoc = createDocument(kvd.value1(), kvd.value2(), Operation.CREATION);
                            if (newDoc == null) {
                                return Observable.empty();
                            } else {
                                return bucket.async().insert(newDoc)
                                .map(new Func1<SerializableDocument, Tuple3<K, V, V>>() {
                                    @Override
                                    public Tuple3<K, V, V> call(SerializableDocument serializableDocument) {
                                        return Tuple.create(kvd.value1(), kvd.value2(), null);
                                    }
                                });
                            }
                        } else {
                            //value in cache, should we update it? (taking expiry into account)
                            SerializableDocument updateDoc = createDocument(kvd.value1(), kvd.value2(), Operation.UPDATE);
                            final V oldValue = (V) kvd.value3().content();
                            if (updateDoc == null || !replaceExistingValues) {
                                return Observable.empty();
                            } else {
                                return bucket.async().replace(updateDoc)
                                .map(new Func1<SerializableDocument, Tuple3<K, V, V>>() {
                                    @Override
                                    public Tuple3<K, V, V> call(SerializableDocument serializableDocument) {
                                         return Tuple.create(kvd.value1(), kvd.value2(), oldValue);
                                     }
                                });
                            }
                        }
                    }
                })
                .subscribe(
                        //for each value, prepare an event notification
                        new Action1<Tuple3<K, V, V>>() {
                            @Override
                            public void call(Tuple3<K, V, V> kvOldValue) {
                                EventType type = EventType.CREATED;
                                if (kvOldValue.value3() != null) {
                                    type = EventType.UPDATED;
                                }
                                eventManager.queueEvent(new CouchbaseCacheEntryEvent<K, V>(type, kvOldValue.value1(),
                                        kvOldValue.value2(), kvOldValue.value3(), CouchbaseCache.this));
                            }
                        },
                        //in case of error dispatch the events and notify the error
                        new Action1<Throwable>() {
                            @Override
                            public void call(Throwable throwable) {
                                eventManager.dispatch();
                                if (completionListener != null && throwable instanceof Exception) {
                                    completionListener.onException((Exception) throwable);
                                }
                            }
                        },
                        //in case of completion, dispatch the events and notify completion
                        new Action0() {
                            @Override
                            public void call() {
                                eventManager.dispatch();
                                if (completionListener != null) {
                                    completionListener.onCompletion();
                                }
                            }
                        });
    }

    @Override
    public void put(K key, V value) {
        //TODO check expiry
        checkOpen();
        checkTypes(key, value);
        long start = configuration.isStatisticsEnabled() ? System.nanoTime() : 0;

        try {
            SerializableDocument oldDocument = bucket.get(toInternalKey(key), SerializableDocument.class);
            SerializableDocument doc = createDocument(key, value, Operation.CREATION);
            //Only do something if doc is not null (otherwise it means expiry was already set)
            if (doc != null) {
                bucket.upsert(doc);
                if (oldDocument != null) {
                    eventManager.queueAndDispatch(EventType.UPDATED, key, value, (V) oldDocument.content(), this);
                } else {
                    eventManager.queueAndDispatch(EventType.CREATED, key, value, this);
                }
                if (configuration.isStatisticsEnabled()) {
                    statisticsMxBean.increaseCachePuts(1L);
                    statisticsMxBean.addPutTimeNano(System.nanoTime() - start);
                }
            }
        } catch (Exception e) {
            throw new CacheException("Error during put of " + key, e);
        }

    }

    @Override
    public V getAndPut(K key, V value) {
        //TODO expiry
        checkOpen();
        checkTypes(key, value);

        long start = configuration.isStatisticsEnabled() ? System.nanoTime() : 0;

        String internalKey = toInternalKey(key);
        SerializableDocument oldValue = bucket.get(internalKey, SerializableDocument.class);

        put(key, value);
        if (configuration.isStatisticsEnabled()) {
            statisticsMxBean.increaseCachePuts(1L);
            if (oldValue == null) {
                statisticsMxBean.increaseCacheMisses(1L);
            } else {
                statisticsMxBean.increaseCacheHits(1L);
            }
            long time = System.nanoTime() - start;
            statisticsMxBean.addGetTimeNano(time);
            statisticsMxBean.addPutTimeNano(time);
        }

        if (oldValue == null) {
            eventManager.queueAndDispatch(EventType.CREATED, key, value, this);
            return null;
        } else {
            V old = (V) oldValue.content();
            eventManager.queueAndDispatch(EventType.UPDATED, key, value, old, this);
            return old;
        }
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
        checkOpen();
        for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        //TODO expiry
        checkOpen();
        checkTypes(key, value);
        String internalKey = toInternalKey(key);
        long start = configuration.isStatisticsEnabled() ? System.nanoTime() : 0;

        SerializableDocument oldDoc = bucket.get(internalKey, SerializableDocument.class);
        if (oldDoc != null) {
            if (isStatisticsEnabled()) {
                statisticsMxBean.increaseCacheHits(1L);
            }
            return false;
        } else {
            try {
                SerializableDocument newDoc = createDocument(key, value, Operation.CREATION);
                if (newDoc != null) {
                    try {
                        bucket.insert(newDoc);
                        eventManager.queueAndDispatch(EventType.CREATED, key, value, this);
                        if (isStatisticsEnabled()) {
                            statisticsMxBean.increaseCacheMisses(1L);
                            statisticsMxBean.increaseCachePuts(1L);
                            statisticsMxBean.addPutTimeNano(System.nanoTime() - start);
                        }
                        return true;
                    } catch (DocumentAlreadyExistsException e) {
                        if (isStatisticsEnabled()) {
                            statisticsMxBean.increaseCacheHits(1L);
                        }
                        return false;
                    }
                } else {
                    //expiry indicates no document to create, assume cache miss
                    if (isStatisticsEnabled()) {
                        statisticsMxBean.increaseCacheMisses(1L);
                    }
                    return false;
                }
            } catch (Exception e) {
                throw new CacheException("Error during putIfAbsent of " + key);
            }
        }
    }

    @Override
    public boolean remove(K key) {
        //TODO expiry
        checkOpen();
        if (key == null) {
            throw new NullPointerException("Removed key cannot be null");
        }
        String internalKey = toInternalKey(key);
        long start = configuration.isStatisticsEnabled() ? System.nanoTime() : 0;

        try {
            SerializableDocument oldDoc = bucket.get(internalKey, SerializableDocument.class);
            if (oldDoc == null) {
                return false;
            } else {
                try {
                    bucket.remove(internalKey); //so that remove ignores cas
                } catch (DocumentDoesNotExistException e) {
                    //we consider it a success (another client competed to remove)
                }
                eventManager.queueAndDispatch(EventType.REMOVED, key, (V) oldDoc.content(), this);
                if (isStatisticsEnabled()) {
                    statisticsMxBean.increaseCacheRemovals(1L);
                    statisticsMxBean.addRemoveTimeNano(System.nanoTime() - start);
                }
                return true;
            }
        } catch (Exception e) {
            throw exception("Couldn't remove " + key, e);
        }
    }

    @Override
    public boolean remove(K key, V oldValue) {
        checkOpen();
        String cbKey = toInternalKey(key);
        long start = configuration.isStatisticsEnabled() ? System.nanoTime() : 0;

        boolean result;
        try {
            SerializableDocument currentDoc = bucket.get(cbKey, SerializableDocument.class);
            V currentValue = currentDoc == null ? null : (V) currentDoc.content();

            if (currentValue == null || !currentValue.equals(oldValue)) {
                result = false;
            } else {
                try {
                    bucket.remove(currentDoc);
                    eventManager.queueAndDispatch(EventType.REMOVED, key, currentValue, this);
                    result = true;
                } catch (DocumentDoesNotExistException e) {
                    //another client competed to remove, still considered a success
                    eventManager.queueAndDispatch(EventType.REMOVED, key, currentValue, this);
                    result = true;
                } catch (CASMismatchException e) {
                    //the value changed, assume it doesn't correspond anymore
                    result = false;
                }
            }

            if (configuration.isStatisticsEnabled()) {
                long time = System.nanoTime() - start;
                if (currentValue == null) {
                    statisticsMxBean.increaseCacheMisses(1L);
                } else {
                    statisticsMxBean.increaseCacheHits(1L);
                    if (result) {
                        statisticsMxBean.increaseCacheRemovals(1L);
                        statisticsMxBean.addRemoveTimeNano(time);
                    }
                }
                statisticsMxBean.addGetTimeNano(time);
            }
            return result;
        } catch (Exception e) {
            throw exception("Couldn't remove " + key + " with old value", e);
        }
    }

    @Override
    public V getAndRemove(K key) {
        checkOpen();
        String cbKey = toInternalKey(key);

        long start = configuration.isStatisticsEnabled() ? System.nanoTime() : 0;

        //TODO expiry, better happen-before in case of CAS mismatch
        try {
            SerializableDocument currentDoc = bucket.get(cbKey, SerializableDocument.class);
            V currentValue = null;

            if (currentDoc != null) {
                currentValue = (V) currentDoc.content();
                try {
                    //still remove and notify with known value even if cas mismatch
                    bucket.remove(cbKey);
                    eventManager.queueAndDispatch(EventType.REMOVED, key, currentValue, this);
                } catch (DocumentDoesNotExistException e) {
                    currentValue = null;
                }
            }

            if (configuration.isStatisticsEnabled()) {
                if (currentValue == null) {
                    statisticsMxBean.increaseCacheMisses(1L);
                } else {
                    statisticsMxBean.increaseCacheRemovals(1L);
                    statisticsMxBean.increaseCacheHits(1L);
                    statisticsMxBean.addRemoveTimeNano(System.nanoTime() - start);
                }
                statisticsMxBean.addGetTimeNano(System.nanoTime() - start);
            }

            return currentValue;
        } catch (Exception e) {
            throw exception("Couldn't getAndRemove " + key,  e);
        }
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        checkOpen();
        String cbKey = toInternalKey(key);
        if (oldValue == null) {
            throw new NullPointerException("OldValue must not be null for key " + key);
        }
        if (newValue == null) {
            throw new NullPointerException("NewValue must not be null for key " + key);
        }
        long start = configuration.isStatisticsEnabled() ? System.nanoTime() : 0L;

        //TODO expiry
        try {
            boolean result;
            SerializableDocument currentDoc = bucket.get(cbKey, SerializableDocument.class);
            V currentValue = currentDoc == null ? null : (V) currentDoc.content();
            if (currentValue != null && currentValue.equals(oldValue)) {
                try {
                    SerializableDocument newDoc = SerializableDocument.create(
                            cbKey, toInternalValue(newValue), currentDoc.cas());
                    bucket.replace(newDoc);
                    eventManager.queueAndDispatch(EventType.UPDATED, key, newValue, currentValue, this);
                    result = true;
                } catch (CASMismatchException e) {
                    result = false;
                } catch (DocumentDoesNotExistException e) {
                    result = false;
                }
            } else {
                result = false;
            }

            if (configuration.isStatisticsEnabled()) {
                long time = System.nanoTime() - start;
                statisticsMxBean.addGetTimeNano(time);
                if (currentValue == null) {
                    statisticsMxBean.increaseCacheMisses(1L);
                } else {
                    statisticsMxBean.increaseCacheHits(1L);
                }

                if (result) {
                    statisticsMxBean.increaseCachePuts(1L);
                    statisticsMxBean.addPutTimeNano(time);
                }
            }

            return result;
        } catch (Exception e) {
            throw exception("Couldn't get old value for replace", e);
        }
    }

    private void internalReplace(K key, V value, V oldValue, String cbKey, Serializable internalValue, long cas) {
        SerializableDocument newDoc = SerializableDocument.create(cbKey, internalValue, cas);
        bucket.replace(newDoc);
        eventManager.queueAndDispatch(EventType.UPDATED, key, value, oldValue, this);
    }

    @Override
    public boolean replace(K key, V value) {
        checkOpen();
        String cbKey = toInternalKey(key);
        Serializable cbValue = toInternalValue(value);
        long start = configuration.isStatisticsEnabled() ? System.nanoTime() : 0L;

        //TODO expiry
        try {
            boolean result;
            SerializableDocument oldDoc = bucket.get(cbKey, SerializableDocument.class);
            V oldValue = oldDoc == null ? null : (V) oldDoc.content();
            if (oldValue == null) {
                result = false;
            } else {
                try {
                    internalReplace(key, value, oldValue, cbKey, cbValue, oldDoc.cas());
                    result = true;
                } catch (CASMismatchException e) {
                    //retry to get the latest value and remove it, this time locking
                    SerializableDocument latest = bucket.getAndLock(cbKey, 1, SerializableDocument.class);
                    V latestValue = latest == null ? null : (V) latest.content();
                    if (latest == null) {
                        result = false;
                    } else {
                        internalReplace(key, value, latestValue, cbKey, cbValue, latest.cas());
                        result = true;
                    }
                } catch (DocumentDoesNotExistException e) {
                    result = false;
                }
            }

            if (configuration.isStatisticsEnabled()) {
                long time = System.nanoTime() - start;
                statisticsMxBean.addGetTimeNano(time);
                if (result) {
                    statisticsMxBean.addPutTimeNano(time);
                    statisticsMxBean.increaseCachePuts(1L);
                    statisticsMxBean.increaseCacheHits(1L);
                } else {
                    statisticsMxBean.increaseCacheMisses(1L);
                }

            }

            return result;
        } catch (Exception e) {
            throw exception("Couldn't replace " + key, e);
        }
    }

    @Override
    public V getAndReplace(K key, V value) {
        checkOpen();
        String cbKey = toInternalKey(key);

        long start = configuration.isStatisticsEnabled() ? System.nanoTime() : 0;
        //TODO expiry

        try {
            SerializableDocument oldDoc = bucket.get(cbKey, SerializableDocument.class);
            V oldValue = oldDoc == null ? null : (V) oldDoc.content();
            if (oldValue != null) {
                Serializable cbValue = toInternalValue(value);
                try {
                    internalReplace(key, value, oldValue, cbKey, cbValue, oldDoc.cas());
                } catch (DocumentDoesNotExistException e) {
                    oldValue = null;
                } catch (CASMismatchException e) {
                    //retry, this time locking
                    SerializableDocument latestDoc = bucket.getAndLock(cbKey, 1, SerializableDocument.class);
                    if (latestDoc == null) {
                        oldValue = null;
                    } else {
                        V latestValue = (V) latestDoc.content();
                        internalReplace(key, value, latestValue, cbKey, cbValue, latestDoc.cas());
                        oldValue = latestValue;
                    }
                }
            }

            if (configuration.isStatisticsEnabled()) {
                long time = System.nanoTime() - start;
                if (oldValue == null) {
                    statisticsMxBean.increaseCacheMisses(1L);
                } else {
                    statisticsMxBean.increaseCacheHits(1L);
                    statisticsMxBean.increaseCachePuts(1L);
                    statisticsMxBean.addPutTimeNano(time);
                }
                statisticsMxBean.addGetTimeNano(time);
            }

            return oldValue;
        } catch (Exception e) {
            throw exception("Couldn't getAndReplace " + key, e);
        }
    }

    @Override
    public void removeAll(Set<? extends K> keys) {
        if (keys == null) {
            throw new NullPointerException("Set of keys cannot be null");
        }
        checkOpen();
        for (K key : keys) {
            remove(key);
        }
    }

    @Override
    public void removeAll() {
        checkOpen();
        long start = configuration.isStatisticsEnabled() ? System.nanoTime() : 0;

        final AtomicLong removedCount = new AtomicLong(0L);
        internalClear(new Action1<SerializableDocument>() {
            @Override
            public void call(SerializableDocument serializableDocument) {
                K key = fromInternalKey(serializableDocument.id());
                V value = (V) serializableDocument.content();
                eventManager.queueEvent(new CouchbaseCacheEntryEvent<K, V>(EventType.REMOVED, key, value,
                        CouchbaseCache.this));
            }
        });
        eventManager.dispatch();

        if (configuration.isStatisticsEnabled() && removedCount.get() > 0L) {
            statisticsMxBean.increaseCacheRemovals(removedCount.get());
            //approximate remove time as an average
            statisticsMxBean.addRemoveTimeNano((System.nanoTime() - start) / removedCount.get());
        }
    }

    @Override
    public <T> T invoke(K key, EntryProcessor<K, V, T> entryProcessor,
            Object... arguments) throws EntryProcessorException {
        checkOpen();
        //TODO stats: puts if setValue called, removals if remove called, hits and misses
        //TODO dispatch correct events

        throw new UnsupportedOperationException();
    }

    @Override
    public <T> Map<K, EntryProcessorResult<T>> invokeAll(Set<? extends K> keys, EntryProcessor<K, V, T> entryProcessor,
            Object... arguments) {
        checkOpen();
        //TODO stats, per key: puts if setValue called, removals if remove called, hits and misses
        //TODO dispatch correct events

        throw new UnsupportedOperationException();
    }

    @Override
    public void registerCacheEntryListener(CacheEntryListenerConfiguration<K, V> config) {
        //keep track of this in the configuration in case it is cloned for another cache
        this.configuration.addCacheEntryListenerConfiguration(config);
        //instantiate the listener in the dispatcher
        this.eventManager.addListener(config);
    }

    @Override
    public void deregisterCacheEntryListener(CacheEntryListenerConfiguration<K, V> config) {
        this.eventManager.removeListener(config);
        this.configuration.removeCacheEntryListenerConfiguration(config);
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
        checkOpen();
        CouchbaseCacheIterator.TimeAndDocHook visitAction = new CouchbaseCacheIterator.TimeAndDocHook() {
            @Override
            public void call(Tuple2<Long, SerializableDocument> timeAndDoc) {
                SerializableDocument serializableDocument = timeAndDoc.value2();
                touchIfNeeded(serializableDocument.id());
                if (isStatisticsEnabled()) {
                    statisticsMxBean.increaseCacheHits(1L);
                    statisticsMxBean.addGetTimeNano(System.nanoTime() - timeAndDoc.value1());
                }
            }
        };
        CouchbaseCacheIterator.TimeAndDocHook removeAction = new CouchbaseCacheIterator.TimeAndDocHook() {
            @Override
            public void call(Tuple2<Long, SerializableDocument> timeAndDoc) {
                K key = keyConverter.fromString(timeAndDoc.value2().id());
                V value = (V) timeAndDoc.value2().content();
                long start = timeAndDoc.value1();

                eventManager.queueAndDispatch(EventType.REMOVED, key, value, CouchbaseCache.this);
                if (isStatisticsEnabled()) {
                    statisticsMxBean.increaseCacheEvictions(1L);
                    statisticsMxBean.addRemoveTimeNano(System.nanoTime() - start);
                }
            }
        };

        return new CouchbaseCacheIterator<K, V>(this.bucket, this.keyConverter,
                getAllKeys(), visitAction, removeAction);
    }

    /**
     * Checks for existence of a view adapted for iteration on all this cache's values.
     *
     * @return a pair of the view's designDoc and name if it exists, throws a {@link CacheException} otherwise.
     * @throws CacheException when a suitable view could not be found.
     * @see CouchbaseConfiguration#getAllViewDesignDoc()
     * @see CouchbaseConfiguration#getAllViewName()
     * @see CouchbaseConfiguration.Builder#viewAll(String, String)
     */
    public String[] checkAndGetViewInfo() {
        String expectedDesignDoc = configuration.getAllViewDesignDoc();
        String expectedViewName = configuration.getAllViewName();
        Exception cause = null;

        try {
            DesignDocument designDoc = bucket.bucketManager().getDesignDocument(expectedDesignDoc);
            if (designDoc == null) {
                cause = new NullPointerException("Design document " + expectedDesignDoc + " does not exist");
            } else {
                for (View view : designDoc.views()) {
                    if (expectedViewName.equalsIgnoreCase(view.name())) {
                        return new String[] { designDoc.name(), view.name() };
                    }
                }
            }
        } catch (Exception e) {
            cause = e;
        }
        throw new CacheException("Cannot find view " + expectedDesignDoc + "/" + expectedViewName
                + " for cache " + getName() + ",did you create it?", cause);
    }

    private Observable<String> getAllKeys() {
        String[] viewInfo = checkAndGetViewInfo();

        return bucket.async()
                     .query(ViewQuery.from(viewInfo[0], viewInfo[1]))
                     .flatMap(new Func1<AsyncViewResult, Observable<AsyncViewRow>>() {
                         @Override
                         public Observable<AsyncViewRow> call(AsyncViewResult asyncViewResult) {
                             return asyncViewResult.rows();
                         }
                     })
                     .map(new Func1<AsyncViewRow, String>() {
                         @Override
                         public String call(AsyncViewRow asyncViewRow) {
                             return asyncViewRow.id();
                         }
                     });
    }

    @Override
    public <C extends Configuration<K, V>> C getConfiguration(Class<C> clazz) {
        if (clazz.isInstance(this.configuration)) {
            return clazz.cast(this.configuration);
        }
        throw new IllegalArgumentException("Configuration class " + clazz.getName() + " not supported");
    }

    @Override
    public <T> T unwrap(Class<T> clazz) {
        if (clazz.isAssignableFrom(this.getClass())) {
            return clazz.cast(this);
        }
        throw new IllegalArgumentException("Cannot unwrap to " + clazz.getName());
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public CacheManager getCacheManager() {
        return this.cacheManager;
    }

    @Override
    public void clear() {
        checkOpen();
        try {
            internalClear(Actions.empty());
        } catch (Exception e) {
            throw new CacheException("Unable to clear", e);
        }
    }

    @Override
    public synchronized void close() {
        if (!isClosed) {
            this.isClosed = true;

            //disable statistics and management
            setStatisticsEnabled(false);
            setManagementEnabled(false);

            //close the configured CacheLoader
            if (cacheLoader instanceof Closeable) {
                try {
                    ((Closeable) cacheLoader).close();
                } catch (IOException e) {
                    Logger.getLogger(this.getName()).log(Level.WARNING, "Problem closing CacheLoader "
                            + cacheLoader.getClass(), e);
                }
            }

            //close the configured CacheWriter
            if (cacheWriter instanceof Closeable) {
                try {
                    ((Closeable) cacheWriter).close();
                } catch (IOException e) {
                    Logger.getLogger(this.getName()).log(Level.WARNING, "Problem closing CacheWriter "
                            + cacheWriter.getClass(), e);
                }
            }

            //close the configured ExpiryPolicy
            if (expiryPolicy instanceof Closeable) {
                try {
                    ((Closeable) expiryPolicy).close();
                } catch (IOException e) {
                    Logger.getLogger(this.getName()).log(Level.WARNING, "Problem closing ExpiryPolicy "
                            + expiryPolicy.getClass(), e);
                }
            }

            //signal the CacheManager that this cache is closed
            this.cacheManager.signalCacheClosed(getName());

            //close the corresponding bucket
            try {
                if (!this.bucket.close()) {
                    LOGGER.warn("Could not close bucket for cache " + getName() + " (returned false)");
                }
            } catch (Exception e) {
                LOGGER.error("Could not close bucket for cache " + getName(), e);
            }
            //TODO other cleanup operations needed to close the cache properly
        }
    }

    @Override
    public boolean isClosed() {
        return this.isClosed;
    }

    /**
     * Convenience method to check status of the cache and throw appropriate exception if it is closed.
     */
    private void checkOpen() {
        if (isClosed()) {
            throw new IllegalStateException("Cache " + name + " closed");
        }
    }

    private void internalClear(Action1<? super SerializableDocument> action) {
        getAllKeys()
                .flatMap(new Func1<String, Observable<SerializableDocument>>() {
                    @Override
                    public Observable<SerializableDocument> call(String id) {
                        return bucket.async().remove(id, SerializableDocument.class);
                    }
                })
                .toBlocking()
                .forEach(action);
    }

    private int getDurationCode(Operation op) {
        ExpiryPolicy policy = configuration.getExpiryPolicyFactory().create();
        Duration expiryDuration = Duration.ZERO;
        switch (op) {
            case CREATION:
                expiryDuration = policy.getExpiryForCreation();
                break;
            case UPDATE:
                expiryDuration = policy.getExpiryForUpdate();
                break;
            case ACCESS:
                expiryDuration = policy.getExpiryForAccess();
                break;
        }

        if (expiryDuration == null) {
            return TTL_DONT_CHANGE;
        } else if (expiryDuration.isZero()) {
            return TTL_EXPIRED;
        } else if (expiryDuration.isEternal()) {
            return TTL_NONE;
        } else {
            long longExpiry = expiryDuration.getTimeUnit().toSeconds(expiryDuration.getDurationAmount());
            if (longExpiry <= MAX_TTL) {
                return (int) longExpiry;
            } else {
                throw new IllegalArgumentException("Explicit " + op.name()
                        + " expiry must be less than 30 days (30 * 24 * 60 * 60 = " + MAX_TTL + "sec)");
            }
        }
    }

    private void touchIfNeeded(String docId) {
        int ttlOrCode = getDurationCode(Operation.ACCESS);
        if (ttlOrCode >= 0) {
            bucket.touch(docId, ttlOrCode);
        }
    }

    /**
     * Depending on the operation, produces a SerializableDocument with correct TTL and a CAS of 0.
     *
     * @param key the key for the document
     * @param value the value to store
     * @param op the operation being performed
     * @return the {@link SerializableDocument} to be persisted, or null if the {@link ExpiryPolicy}
     *  indicates a TTL already expired
     * @throws IllegalArgumentException when the {@link ExpiryPolicy} produces a TTL > 30 days
     */
    private SerializableDocument createDocument(K key, V value, Operation op) {
        return createDocument(key, value, op, 0L);
    }

    /**
     * Depending on the operation, produces a SerializableDocument with correct TTL and CAS.
     *
     * @param key the key for the document
     * @param value the value to store
     * @param op the operation being performed
     * @param cas the cas of the document (or 0 if none needed)
     * @return the {@link SerializableDocument} to be persisted, or null if the {@link ExpiryPolicy}
     *  indicates a TTL already expired
     * @throws IllegalArgumentException when the {@link ExpiryPolicy} produces a TTL > 30 days
     */
    private SerializableDocument createDocument(K key, V value, Operation op, long cas) {
        String cbKey = toInternalKey(key);
        Serializable cbValue = toInternalValue(value);
        int ttlOrCode = getDurationCode(op);
        switch (ttlOrCode) {
            case TTL_DONT_CHANGE:
                return SerializableDocument.create(cbKey, cbValue, cas);
            case TTL_NONE:
                return SerializableDocument.create(cbKey, cbValue, cas);
            case TTL_EXPIRED:
                return null;
            default:
                if (ttlOrCode < 0) {
                    throw new IllegalArgumentException("Unknown ttl code " + ttlOrCode);
                } else {
                    return SerializableDocument.create(cbKey, ttlOrCode, cbValue, cas);
                }
        }
    }

    protected String toInternalKey(K key) {
        if (key == null) {
            throw new NullPointerException("Keys must not be null");
        }
        return this.keyConverter.asString(key);
    }

    protected K fromInternalKey(String internalKey) {
        if (internalKey == null) {
            throw new NullPointerException("Internal key must not be null");
        }
        return this.keyConverter.fromString(internalKey);
    }

    private Serializable toInternalValue(V value) {
        if (value instanceof Serializable) {
            return (Serializable) value;
        } else {
            throw new ClassCastException("This cache can only accept Serializable values");
        }
    }

    private CacheException exception(String message, Exception e) {
        if (e instanceof CacheException) {
            return (CacheException) e;
        } else {
            return new CacheException(message, e);
        }
    }

    private void checkTypes(K key, V value) {
        Class<?> keyType = key.getClass();
        Class<?> valueType = value.getClass();

        Class<?> confKeyType = configuration.getKeyType();
        Class<?> confValueType = configuration.getValueType();

        if (confKeyType != Object.class && !confKeyType.isAssignableFrom(keyType)) {
            throw new ClassCastException("Keys are required to be of type " + confKeyType.getName());
        }
        if (confValueType != Object.class && !confValueType.isAssignableFrom(valueType)) {
            throw new ClassCastException("Values are required to be of type " + confValueType.getName());
        }

    }

    private static enum Operation {
        CREATION,
        UPDATE,
        ACCESS
    }
}
