package org.cache2k.core;

/*
 * #%L
 * cache2k implementation
 * %%
 * Copyright (C) 2000 - 2020 headissue GmbH, Munich
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.cache2k.Cache;
import org.cache2k.CacheEntry;
import org.cache2k.CacheException;
import org.cache2k.CustomizationException;
import org.cache2k.configuration.CustomizationSupplier;
import org.cache2k.core.operation.Operations;
import org.cache2k.jmx.CacheInfoMXBean;
import org.cache2k.processor.EntryProcessingException;
import org.cache2k.processor.EntryProcessor;
import org.cache2k.processor.EntryProcessingResult;
import org.cache2k.core.operation.Semantic;
import org.cache2k.core.storageApi.StorageAdapter;

import java.io.Closeable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

/**
 * Some default implementations for a cache.
 *
 * @author Jens Wilke
 */
public abstract class BaseCache<K, V> implements InternalCache<K, V> {

  public abstract Executor getExecutor();

  protected abstract <R> EntryAction<K,V,R> createFireAndForgetAction(
    final Entry<K, V> e, final Semantic<K,V,R> op);

  /**
   *
   * @see HeapCache#getCompleteName() similar
   */
  public static String nameQualifier(Cache cache) {
    StringBuilder sb = new StringBuilder();
    sb.append('\'').append(cache.getName()).append('\'');
    if (!cache.getCacheManager(). isDefaultManager()) {
      sb.append(", manager='").append(cache.getCacheManager().getName()).append('\'');
    }
    return sb.toString();
  }

  protected abstract Iterator<CacheEntry<K, V>> iterator();

  /**
   * Key iteration on top of normal iterator.
   */
  @Override
  public Iterable<K> keys() {
    return new Iterable<K>() {
      @Override
      public Iterator<K> iterator() {
        final Iterator<CacheEntry<K,V>> it = BaseCache.this.iterator();
        return new Iterator<K>() {
          @Override
          public boolean hasNext() {
            return it.hasNext();
          }

          @Override
          public K next() {
            return it.next().getKey();
          }

          @Override
          public void remove() {
            throw new UnsupportedOperationException();
          }
        };
      }
    };
  }

  @Override
  public Iterable<CacheEntry<K, V>> entries() {
    return new Iterable<CacheEntry<K, V>>() {
      @Override
      public Iterator<CacheEntry<K, V>> iterator() {
        return BaseCache.this.iterator();
      }
    };
  }

  @Override
  public void removeAll() {
    removeAll(keys());
  }

  @Override
  public void removeAll(Iterable<? extends K> _keys) {
    for (K k : _keys) {
      remove(k);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public <X> X requestInterface(Class<X> _type) {
    if (_type.equals(ConcurrentMap.class) ||
      _type.equals(Map.class)) {
      return (X) new ConcurrentMapWrapper<K, V>(this);
    }
    if (_type.isAssignableFrom(this.getClass())) {
      return (X) this;
    }
    return null;
  }

  @Override
  public ConcurrentMap<K, V> asMap() {
    return new ConcurrentMapWrapper<K, V>(this);
  }

  @Override
  public StorageAdapter getStorage() { return null; }

  @Override
  public <R> Map<K, EntryProcessingResult<R>> invokeAll(Iterable<? extends K> keys, EntryProcessor<K, V, R> entryProcessor) {
    Map<K, EntryProcessingResult<R>> m = new HashMap<K, EntryProcessingResult<R>>();
    for (K k : keys) {
      try {
        final R _result = invoke(k, entryProcessor);
        if (_result == null) {
          continue;
        }
        m.put(k, new EntryProcessingResult<R>() {
          @Override
          public R getResult() {
            return _result;
          }

          @Override
          public Throwable getException() {
            return null;
          }
        });
      } catch (EntryProcessingException t) {
        final Throwable _cause = t.getCause();
        m.put(k, new EntryProcessingResult<R>() {
          @Override
          public R getResult() {
            throw new EntryProcessingException(_cause);
          }

          @Override
          public Throwable getException() {
            return _cause;
          }
        });
      }
    }
    return m;
  }

  @Override
  public void expireAt(final K key, final long _millis) {
    execute(key, Operations.SINGLETON.expire(key, _millis));
  }

  protected <R> R execute(K key, Entry<K, V> e, Semantic<K, V, R> op) {
    EntryAction<K, V, R> _action = createEntryAction(key, e, op);
    return execute(_action);
  }

  protected abstract <R> EntryAction<K, V, R> createEntryAction(K key, Entry<K, V> e, Semantic<K, V, R> op);

  protected <R> R execute(final EntryAction<K, V, R> _action) {
    _action.start();
    return finishExecution(_action);
  }

  /**
   * Not used for async, async will always report the exception via the callback.
   */
  protected <R> R finishExecution(final EntryAction<K, V, R> _action) {
    RuntimeException t = _action.exceptionToPropagate;
    if (t != null) {
      t.fillInStackTrace();
      throw t;
    }
    return _action.result;
  }

  protected <R> R execute(K key, Semantic<K, V, R> op) {
    return execute(key, null, op);
  }


  @Override
  public <T> T createCustomization(final CustomizationSupplier<T> f) {
    if (f == null) {
      return null;
    }
    try {
      return f.supply(getCacheManager());
    } catch (Exception ex) {
      throw new CustomizationException("Initialization of customization failed", ex);
    }
  }

  @Override
  public void closeCustomization(final Object _customization, String _customizationName) {
    if (_customization instanceof Closeable) {
      try {
        ((Closeable) _customization).close();
      } catch (Exception e) {
        String _message =
          _customizationName + ".close() exception (" + nameQualifier(this) + ")";
        throw new CacheException(_message, e);
      }
    }
  }

  @Override
  public void clearAndClose() {
    close();
  }

  /**
   * Return status information. The status collection is time consuming, so this
   * is an expensive operation.
   */
  @Override
  public String toString() {
    try {
      InternalCacheInfo fo = getLatestInfo();
      return fo.toString();
    } catch (CacheClosedException ex) {
      return "Cache(name=" + BaseCache.nameQualifier (this) + ", closed=true)";
    }
  }

  @Override
  public CacheInfoMXBean getStatistics() {
    return new CacheMXBeanImpl(this);
  }

}
