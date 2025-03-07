/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.encryption;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockFactory;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.MMapDirectoryFactory;
import org.apache.solr.encryption.crypto.AesCtrEncrypterFactory;
import org.apache.solr.encryption.crypto.CipherAesCtrEncrypter;

import java.io.IOException;

/**
 * Creates an {@link EncryptionDirectory} delegating to a {@link org.apache.lucene.store.MMapDirectory}.
 * <p/>
 * To be configured with two parameters:
 * <ul>
 *   <li>{@link #PARAM_KEY_MANAGER_SUPPLIER} defines the {@link KeyManager.Supplier} to use.
 *   Required.</li>
 *   <li>{@link #PARAM_ENCRYPTER_FACTORY} defines which {@link AesCtrEncrypterFactory} to use.
 *   Default is {@link CipherAesCtrEncrypter.Factory}.</li>
 * </ul>
 * <pre>
 *   <directoryFactory name="DirectoryFactory"
 *       class="${solr.directoryFactory:org.apache.solr.encryption.EncryptionDirectoryFactory}">
 *     <str name="keyManagerSupplier">${solr.keyManagerSupplier:com.myproject.MyKeyManagerSupplier}</str>
 *     <str name="encrypterFactory">${solr.encrypterFactory:org.apache.solr.encryption.crypto.LightAesCtrEncrypter$Factory}</str>
 *   </directoryFactory>
 * </pre>
 */
public class EncryptionDirectoryFactory extends MMapDirectoryFactory {

  // TODO: Ideally EncryptionDirectoryFactory would extend a DelegatingDirectoryFactory to delegate
  //  to any other DirectoryFactory. There is a waiting Jira issue SOLR-15060 for that because
  //  a DelegatingDirectoryFactory is not straightforward. There is the tricky case of the
  //  CachingDirectoryFactory (extended by most DirectoryFactory implementations) that currently
  //  creates the Directory itself, so it would not be our delegating Directory.
  //  Right now, EncryptionDirectoryFactory extends MMapDirectoryFactory. And we hope we will
  //  refactor later.

  public static final String PARAM_KEY_MANAGER_SUPPLIER = "keyManagerSupplier";
  public static final String PARAM_ENCRYPTER_FACTORY = "encrypterFactory";
  /**
   * Visible for tests only - Property defining the class name of the inner encryption directory factory.
   */
  static final String PROPERTY_INNER_ENCRYPTION_DIRECTORY_FACTORY = "innerEncryptionDirectoryFactory";

  private KeyManager keyManager;
  private AesCtrEncrypterFactory encrypterFactory;
  private InnerFactory innerFactory;

  @Override
  public void init(NamedList<?> args) {
    super.init(args);
    SolrParams params = args.toSolrParams();

    String keyManagerSupplierClass = params.get(PARAM_KEY_MANAGER_SUPPLIER);
    if (keyManagerSupplierClass == null) {
      throw new IllegalArgumentException("Missing " + PARAM_KEY_MANAGER_SUPPLIER + " argument for " + getClass().getName());
    }
    KeyManager.Supplier keyManagerSupplier = coreContainer.getResourceLoader().newInstance(keyManagerSupplierClass,
                                                                                           KeyManager.Supplier.class);
    keyManagerSupplier.init(args);
    keyManager = keyManagerSupplier.getKeyManager();

    String encrypterFactoryClass = params.get(PARAM_ENCRYPTER_FACTORY, CipherAesCtrEncrypter.Factory.class.getName());
    encrypterFactory = coreContainer.getResourceLoader().newInstance(encrypterFactoryClass,
                                                                     AesCtrEncrypterFactory.class);
    if (!encrypterFactory.isSupported()) {
      throw new UnsupportedOperationException(getClass().getName() + " cannot create an encrypterFactory of type "
                                                + encrypterFactory.getClass().getName(),
                                              encrypterFactory.getUnsupportedCause());
    }

    innerFactory = createInnerFactory();
  }

  private InnerFactory createInnerFactory() {
    String factoryClassName = System.getProperty(PROPERTY_INNER_ENCRYPTION_DIRECTORY_FACTORY);
    if (factoryClassName == null) {
      return EncryptionDirectory::new;
    }
    try {
      return (InnerFactory) EncryptionDirectoryFactory.class.getClassLoader()
        .loadClass(factoryClassName).getDeclaredConstructor().newInstance();
    } catch (Exception e) {
      throw new RuntimeException("Cannot load custom inner directory factory " + factoryClassName, e);
    }
  }

  /** Gets the {@link KeyManager} used by this factory and all the encryption directories it creates. */
  public KeyManager getKeyManager() {
    return keyManager;
  }

  @Override
  protected Directory create(String path, LockFactory lockFactory, DirContext dirContext) throws IOException {
    return innerFactory.create(super.create(path, lockFactory, dirContext), encrypterFactory, getKeyManager());
  }

  /**
   * Visible for tests only - Inner factory that creates {@link EncryptionDirectory} instances.
   */
  interface InnerFactory {
    EncryptionDirectory create(Directory delegate, AesCtrEncrypterFactory encrypterFactory, KeyManager keyManager)
      throws IOException;
  }
}
