/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.wildfly.clustering.web.infinispan.session;

import javax.transaction.SystemException;

import org.infinispan.Cache;
import org.infinispan.commons.CacheException;
import org.infinispan.context.Flag;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntriesEvicted;
import org.infinispan.notifications.cachelistener.event.CacheEntriesEvictedEvent;
import org.wildfly.clustering.ee.Mutator;
import org.wildfly.clustering.ee.MutatorFactory;
import org.wildfly.clustering.ee.cache.CacheProperties;
import org.wildfly.clustering.ee.infinispan.InfinispanMutatorFactory;
import org.wildfly.clustering.infinispan.spi.distribution.Key;
import org.wildfly.clustering.web.cache.session.CompositeSessionMetaData;
import org.wildfly.clustering.web.cache.session.CompositeSessionMetaDataEntry;
import org.wildfly.clustering.web.cache.session.InvalidatableSessionMetaData;
import org.wildfly.clustering.web.cache.session.MutableSessionAccessMetaData;
import org.wildfly.clustering.web.cache.session.MutableSessionCreationMetaData;
import org.wildfly.clustering.web.cache.session.SessionAccessMetaData;
import org.wildfly.clustering.web.cache.session.SessionCreationMetaData;
import org.wildfly.clustering.web.cache.session.SessionCreationMetaDataEntry;
import org.wildfly.clustering.web.cache.session.SessionMetaDataFactory;
import org.wildfly.clustering.web.cache.session.SimpleSessionAccessMetaData;
import org.wildfly.clustering.web.cache.session.SimpleSessionCreationMetaData;
import org.wildfly.clustering.web.session.ImmutableSessionMetaData;

/**
 * @author Paul Ferraro
 */
@Listener(sync = false)
public class InfinispanSessionMetaDataFactory<L> implements SessionMetaDataFactory<CompositeSessionMetaDataEntry<L>, L> {

    private final Cache<SessionCreationMetaDataKey, SessionCreationMetaDataEntry<L>> creationMetaDataCache;
    private final MutatorFactory<SessionCreationMetaDataKey, SessionCreationMetaDataEntry<L>> creationMetaDataMutatorFactory;
    private final Cache<SessionCreationMetaDataKey, SessionCreationMetaDataEntry<L>> findCreationMetaDataCache;
    private final Cache<SessionAccessMetaDataKey, SessionAccessMetaData> accessMetaDataCache;
    private final MutatorFactory<SessionAccessMetaDataKey, SessionAccessMetaData> accessMetaDataMutatorFactory;
    private final CacheProperties properties;

    @SuppressWarnings("unchecked")
    public InfinispanSessionMetaDataFactory(Cache<? extends Key<String>, ?> cache, CacheProperties properties) {
        this.creationMetaDataCache = (Cache<SessionCreationMetaDataKey, SessionCreationMetaDataEntry<L>>) cache;
        this.findCreationMetaDataCache = properties.isLockOnRead() ? this.creationMetaDataCache.getAdvancedCache().withFlags(Flag.FORCE_WRITE_LOCK) : this.creationMetaDataCache;
        this.accessMetaDataCache = (Cache<SessionAccessMetaDataKey, SessionAccessMetaData>) cache;
        this.properties = properties;
        this.creationMetaDataMutatorFactory = new InfinispanMutatorFactory<>(this.creationMetaDataCache, properties);
        this.accessMetaDataMutatorFactory = new InfinispanMutatorFactory<>(this.accessMetaDataCache, properties);
    }

    @Override
    public CompositeSessionMetaDataEntry<L> createValue(String id, Void context) {
        SessionCreationMetaDataEntry<L> creationMetaDataEntry = new SessionCreationMetaDataEntry<>(new SimpleSessionCreationMetaData());
        if (this.creationMetaDataCache.getAdvancedCache().withFlags(Flag.FORCE_SYNCHRONOUS).putIfAbsent(new SessionCreationMetaDataKey(id), creationMetaDataEntry) != null) {
            return null;
        }
        SessionAccessMetaData accessMetaData = new SimpleSessionAccessMetaData();
        this.accessMetaDataCache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES).put(new SessionAccessMetaDataKey(id), accessMetaData);
        return new CompositeSessionMetaDataEntry<>(creationMetaDataEntry.getMetaData(), accessMetaData, creationMetaDataEntry.getLocalContext());
    }

    @Override
    public CompositeSessionMetaDataEntry<L> findValue(String id) {
        return this.getValue(id, this.findCreationMetaDataCache);
    }

    @Override
    public CompositeSessionMetaDataEntry<L> tryValue(String id) {
        return this.getValue(id, this.findCreationMetaDataCache.getAdvancedCache().withFlags(Flag.ZERO_LOCK_ACQUISITION_TIMEOUT, Flag.FAIL_SILENTLY));
    }

    private CompositeSessionMetaDataEntry<L> getValue(String id, Cache<SessionCreationMetaDataKey, SessionCreationMetaDataEntry<L>> creationMetaDataCache) {
        SessionCreationMetaDataKey key = new SessionCreationMetaDataKey(id);
        SessionCreationMetaDataEntry<L> creationMetaDataEntry = creationMetaDataCache.get(key);
        if (creationMetaDataEntry != null) {
            SessionAccessMetaData accessMetaData = this.accessMetaDataCache.get(new SessionAccessMetaDataKey(id));
            if (accessMetaData != null) {
                return new CompositeSessionMetaDataEntry<>(creationMetaDataEntry.getMetaData(), accessMetaData, creationMetaDataEntry.getLocalContext());
            }
            // Purge orphaned entry, making sure not to trigger cache listener
            creationMetaDataCache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES, Flag.SKIP_LISTENER_NOTIFICATION).remove(key);
        }
        return null;
    }

    @Override
    public InvalidatableSessionMetaData createSessionMetaData(String id, CompositeSessionMetaDataEntry<L> entry) {
        boolean created = entry.getAccessMetaData().getLastAccessedDuration().isZero();
        SessionCreationMetaDataKey creationMetaDataKey = new SessionCreationMetaDataKey(id);
        Mutator creationMutator = this.properties.isTransactional() && created ? Mutator.PASSIVE : this.creationMetaDataMutatorFactory.createMutator(creationMetaDataKey, new SessionCreationMetaDataEntry<>(entry.getCreationMetaData(), entry.getLocalContext()));
        SessionCreationMetaData creationMetaData = new MutableSessionCreationMetaData(entry.getCreationMetaData(), creationMutator);

        SessionAccessMetaDataKey accessMetaDataKey = new SessionAccessMetaDataKey(id);
        Mutator accessMutator = this.properties.isTransactional() && created ? Mutator.PASSIVE : this.accessMetaDataMutatorFactory.createMutator(accessMetaDataKey, entry.getAccessMetaData());
        SessionAccessMetaData accessMetaData = new MutableSessionAccessMetaData(entry.getAccessMetaData(), accessMutator);

        return new CompositeSessionMetaData(creationMetaData, accessMetaData);
    }

    @Override
    public ImmutableSessionMetaData createImmutableSessionMetaData(String id, CompositeSessionMetaDataEntry<L> entry) {
        return new CompositeSessionMetaData(entry.getCreationMetaData(), entry.getAccessMetaData());
    }

    @Override
    public boolean remove(String id) {
        return this.remove(id, this.creationMetaDataCache);
    }

    @Override
    public boolean purge(String id) {
        return this.remove(id, this.creationMetaDataCache.getAdvancedCache().withFlags(Flag.SKIP_LISTENER_NOTIFICATION));
    }

    private boolean remove(String id, Cache<SessionCreationMetaDataKey, SessionCreationMetaDataEntry<L>> creationMetaDataCache) {
        SessionCreationMetaDataKey key = new SessionCreationMetaDataKey(id);
        try {
            if (!this.properties.isLockOnWrite() || (creationMetaDataCache.getAdvancedCache().getTransactionManager().getTransaction() == null) || creationMetaDataCache.getAdvancedCache().withFlags(Flag.ZERO_LOCK_ACQUISITION_TIMEOUT, Flag.FAIL_SILENTLY).lock(key)) {
                creationMetaDataCache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES).remove(key);
                this.accessMetaDataCache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES).remove(new SessionAccessMetaDataKey(id));
                return true;
            }
            return false;
        } catch (SystemException e) {
            throw new CacheException(e);
        }
    }

    @CacheEntriesEvicted
    public void evicted(CacheEntriesEvictedEvent<Key<String>, ?> event) {
        if (!event.isPre()) {
            Cache<SessionAccessMetaDataKey, SessionAccessMetaData> cache = this.accessMetaDataCache.getAdvancedCache().withFlags(Flag.SKIP_LISTENER_NOTIFICATION);
            for (Key<String> key : event.getEntries().keySet()) {
                // Workaround for ISPN-8324
                if (key instanceof SessionCreationMetaDataKey) {
                    cache.evict(new SessionAccessMetaDataKey(key.getValue()));
                }
            }
        }
    }
}
