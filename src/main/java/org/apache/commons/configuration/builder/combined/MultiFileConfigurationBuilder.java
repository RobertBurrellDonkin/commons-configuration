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
package org.apache.commons.configuration.builder.combined;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.ConfigurationUtils;
import org.apache.commons.configuration.FileBasedConfiguration;
import org.apache.commons.configuration.builder.BasicBuilderParameters;
import org.apache.commons.configuration.builder.BasicConfigurationBuilder;
import org.apache.commons.configuration.builder.BuilderListener;
import org.apache.commons.configuration.builder.BuilderParameters;
import org.apache.commons.configuration.builder.ConfigurationBuilder;
import org.apache.commons.configuration.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration.event.ConfigurationErrorListener;
import org.apache.commons.configuration.event.ConfigurationListener;
import org.apache.commons.configuration.interpol.ConfigurationInterpolator;
import org.apache.commons.configuration.interpol.InterpolatorSpecification;
import org.apache.commons.lang3.concurrent.ConcurrentUtils;

/**
 * <p>
 * A specialized {@code ConfigurationBuilder} implementation providing access to
 * multiple file-based configurations based on a file name pattern.
 * </p>
 * <p>
 * This builder class is initialized with a pattern string and a
 * {@link ConfigurationInterpolator} object. Each time a configuration is
 * requested, the pattern is evaluated against the
 * {@code ConfigurationInterpolator} (so all variables are replaced by their
 * current values). The resulting string is interpreted as a file name for a
 * configuration file to be loaded. For example, providing a pattern of
 * <em>file:///opt/config/${product}/${client}/config.xml</em> will result in
 * <em>product</em> and <em>client</em> being resolved on every call. By storing
 * configuration files in a corresponding directory structure, specialized
 * configuration files associated with a specific product and client can be
 * loaded. Thus an application can be made multi-tenant in a transparent way.
 * </p>
 * <p>
 * This builder class keeps a map with configuration builders for configurations
 * already loaded. The {@code getConfiguration()} method first evaluates the
 * pattern string and checks whether a builder for the resulting file name is
 * available. If yes, it is queried for its configuration. Otherwise, a new
 * file-based configuration builder is created now and initialized.
 * </p>
 * <p>
 * Configuration of an instance happens in the usual way for configuration
 * builders. A {@link MultiFileBuilderParametersImpl} parameters object is
 * expected which must contain a file name pattern string and a
 * {@code ConfigurationInterpolator}. Other properties of this parameters object
 * are used to initialize the builders for managed configurations.
 * </p>
 *
 * @version $Id$
 * @since 2.0
 * @param <T> the concrete type of {@code Configuration} objects created by this
 *        builder
 */
public class MultiFileConfigurationBuilder<T extends FileBasedConfiguration>
        extends BasicConfigurationBuilder<T>
{
    /**
     * Constant for the name of the key referencing the
     * {@code ConfigurationInterpolator} in this builder's parameters.
     */
    private static final String KEY_INTERPOLATOR = "interpolator";

    /** A cache for already created managed builders. */
    private final ConcurrentMap<String, FileBasedConfigurationBuilder<T>> managedBuilders =
            new ConcurrentHashMap<String, FileBasedConfigurationBuilder<T>>();

    /** Stores the {@code ConfigurationInterpolator} object. */
    private final AtomicReference<ConfigurationInterpolator> interpolator =
            new AtomicReference<ConfigurationInterpolator>();

    /**
     * A flag for preventing reentrant access to managed builders on
     * interpolation of the file name pattern.
     */
    private final ThreadLocal<Boolean> inInterpolation =
            new ThreadLocal<Boolean>();

    /**
     * A specialized builder listener which gets registered at all managed
     * builders. This listener just propagates notifications from managed
     * builders to the listeners registered at this
     * {@code MultiFileConfigurationBuilder}.
     */
    private final BuilderListener managedBuilderDelegationListener =
            new BuilderListener()
            {
                public void builderReset(
                        ConfigurationBuilder<? extends Configuration> builder)
                {
                    resetResult();
                }
            };

    /**
     * Creates a new instance of {@code MultiFileConfigurationBuilder} and sets
     * initialization parameters and a flag whether initialization failures
     * should be ignored.
     *
     * @param resCls the result configuration class
     * @param params a map with initialization parameters
     * @param allowFailOnInit a flag whether initialization errors should be
     *        ignored
     * @throws IllegalArgumentException if the result class is <b>null</b>
     */
    public MultiFileConfigurationBuilder(Class<T> resCls,
            Map<String, Object> params, boolean allowFailOnInit)
    {
        super(resCls, params, allowFailOnInit);
    }

    /**
     * Creates a new instance of {@code MultiFileConfigurationBuilder} and sets
     * initialization parameters.
     *
     * @param resCls the result configuration class
     * @param params a map with initialization parameters
     * @throws IllegalArgumentException if the result class is <b>null</b>
     */
    public MultiFileConfigurationBuilder(Class<T> resCls,
            Map<String, Object> params)
    {
        super(resCls, params);
    }

    /**
     * Creates a new instance of {@code MultiFileConfigurationBuilder} without
     * setting initialization parameters.
     *
     * @param resCls the result configuration class
     * @throws IllegalArgumentException if the result class is <b>null</b>
     */
    public MultiFileConfigurationBuilder(Class<T> resCls)
    {
        super(resCls);
    }

    /**
     * {@inheritDoc} This implementation evaluates the file name pattern using
     * the configured {@code ConfigurationInterpolator}. If this file has
     * already been loaded, the corresponding builder is accessed. Otherwise, a
     * new builder is created for loading this configuration file.
     */
    @Override
    public T getConfiguration() throws ConfigurationException
    {
        return getManagedBuilder().getConfiguration();
    }

    /**
     * Returns the managed {@code FileBasedConfigurationBuilder} for the current
     * file name pattern. It is determined based on the evaluation of the file
     * name pattern using the configured {@code ConfigurationInterpolator}. If
     * this is the first access to this configuration file, the builder is
     * created.
     *
     * @return the configuration builder for the configuration corresponding to
     *         the current evaluation of the file name pattern
     * @throws ConfigurationException if the builder cannot be determined (e.g.
     *         due to missing initialization parameters)
     */
    public FileBasedConfigurationBuilder<T> getManagedBuilder()
            throws ConfigurationException
    {
        Map<String, Object> params = getParameters();
        MultiFileBuilderParametersImpl multiParams =
                MultiFileBuilderParametersImpl.fromParameters(params, true);
        if (multiParams.getFilePattern() == null)
        {
            throw new ConfigurationException("No file name pattern is set!");
        }
        String fileName = fetchFileName(multiParams);

        FileBasedConfigurationBuilder<T> builder =
                getManagedBuilders().get(fileName);
        if (builder == null)
        {
            builder =
                    createInitializedManagedBuilder(fileName,
                            createManagedBuilderParameters(params, multiParams));
            FileBasedConfigurationBuilder<T> newBuilder =
                    ConcurrentUtils.putIfAbsent(getManagedBuilders(), fileName,
                            builder);
            if (newBuilder == builder)
            {
                initListeners(newBuilder);
            }
            else
            {
                builder = newBuilder;
            }
        }
        return builder;
    }

    /**
     * {@inheritDoc} This implementation ensures that the listener is also added
     * at managed configuration builders.
     */
    @Override
    public synchronized BasicConfigurationBuilder<T> addConfigurationListener(
            ConfigurationListener l)
    {
        super.addConfigurationListener(l);
        for (FileBasedConfigurationBuilder<T> b : getManagedBuilders().values())
        {
            b.addConfigurationListener(l);
        }
        return this;
    }

    /**
     * {@inheritDoc} This implementation ensures that the listener is also
     * removed from managed configuration builders.
     */
    @Override
    public synchronized BasicConfigurationBuilder<T> removeConfigurationListener(
            ConfigurationListener l)
    {
        super.removeConfigurationListener(l);
        for (FileBasedConfigurationBuilder<T> b : getManagedBuilders().values())
        {
            b.removeConfigurationListener(l);
        }
        return this;
    }

    /**
     * {@inheritDoc} This implementation ensures that the listener is also added
     * at managed configuration builders.
     */
    @Override
    public synchronized BasicConfigurationBuilder<T> addErrorListener(
            ConfigurationErrorListener l)
    {
        super.addErrorListener(l);
        for (FileBasedConfigurationBuilder<T> b : getManagedBuilders().values())
        {
            b.addErrorListener(l);
        }
        return this;
    }

    /**
     * {@inheritDoc} This implementation ensures that the listener is also
     * removed from managed configuration builders.
     */
    @Override
    public synchronized BasicConfigurationBuilder<T> removeErrorListener(
            ConfigurationErrorListener l)
    {
        super.removeErrorListener(l);
        for (FileBasedConfigurationBuilder<T> b : getManagedBuilders().values())
        {
            b.removeErrorListener(l);
        }
        return this;
    }

    /**
     * {@inheritDoc} This implementation clears the cache with all managed
     * builders.
     */
    @Override
    public synchronized void resetParameters()
    {
        for (FileBasedConfigurationBuilder<T> b : getManagedBuilders().values())
        {
            b.removeBuilderListener(managedBuilderDelegationListener);
        }
        getManagedBuilders().clear();
        interpolator.set(null);
        super.resetParameters();
    }

    /**
     * Returns the {@code ConfigurationInterpolator} used by this instance. This
     * is the object used for evaluating the file name pattern. It is created on
     * demand.
     *
     * @return the {@code ConfigurationInterpolator}
     */
    protected ConfigurationInterpolator getInterpolator()
    {
        ConfigurationInterpolator result;
        boolean done;

        // This might create multiple instances under high load,
        // however, always the same instance is returned.
        do
        {
            result = interpolator.get();
            if (result != null)
            {
                done = true;
            }
            else
            {
                result = createInterpolator();
                done = interpolator.compareAndSet(null, result);
            }
        } while (!done);

        return result;
    }

    /**
     * Creates the {@code ConfigurationInterpolator} to be used by this
     * instance. This method is called when a file name is to be constructed,
     * but no current {@code ConfigurationInterpolator} instance is available.
     * It obtains an instance from this builder's parameters. If no properties
     * of the {@code ConfigurationInterpolator} are specified in the parameters,
     * a default instance without lookups is returned (which is probably not
     * very helpful).
     *
     * @return the {@code ConfigurationInterpolator} to be used
     */
    protected ConfigurationInterpolator createInterpolator()
    {
        InterpolatorSpecification spec =
                BasicBuilderParameters
                        .fetchInterpolatorSpecification(getParameters());
        return ConfigurationInterpolator.fromSpecification(spec);
    }

    /**
     * Determines the file name of a configuration based on the file name
     * pattern. This method is called on every access to this builder's
     * configuration. It obtains the {@link ConfigurationInterpolator} from this
     * builder's parameters and uses it to interpolate the file name pattern.
     *
     * @param multiParams the parameters object for this builder
     * @return the name of the configuration file to be loaded
     */
    protected String constructFileName(
            MultiFileBuilderParametersImpl multiParams)
    {
        ConfigurationInterpolator ci = getInterpolator();
        return String.valueOf(ci.interpolate(multiParams.getFilePattern()));
    }

    /**
     * Creates a builder for a managed configuration. This method is called
     * whenever a configuration for a file name is requested which has not yet
     * been loaded. The passed in map with parameters is populated from this
     * builder's configuration (i.e. the basic parameters plus the optional
     * parameters for managed builders). This base implementation creates a
     * standard builder for file-based configurations. Derived classes may
     * override it to create special purpose builders.
     *
     * @param fileName the name of the file to be loaded
     * @param params a map with initialization parameters for the new builder
     * @return the newly created builder instance
     * @throws ConfigurationException if an error occurs
     */
    protected FileBasedConfigurationBuilder<T> createManagedBuilder(
            String fileName, Map<String, Object> params)
            throws ConfigurationException
    {
        return new FileBasedConfigurationBuilder<T>(getResultClass(), params,
                isAllowFailOnInit());
    }

    /**
     * Creates a fully initialized builder for a managed configuration. This
     * method is called by {@code getConfiguration()} whenever a configuration
     * file is requested which has not yet been loaded. This implementation
     * delegates to {@code createManagedBuilder()} for actually creating the
     * builder object. Then it sets the location to the configuration file.
     *
     * @param fileName the name of the file to be loaded
     * @param params a map with initialization parameters for the new builder
     * @return the newly created and initialized builder instance
     * @throws ConfigurationException if an error occurs
     */
    protected FileBasedConfigurationBuilder<T> createInitializedManagedBuilder(
            String fileName, Map<String, Object> params)
            throws ConfigurationException
    {
        FileBasedConfigurationBuilder<T> managedBuilder =
                createManagedBuilder(fileName, params);
        managedBuilder.getFileHandler().setFileName(fileName);
        return managedBuilder;
    }

    /**
     * Returns the map with the managed builders created so far by this
     * {@code MultiFileConfigurationBuilder}. This map is exposed to derived
     * classes so they can access managed builders directly. However, derived
     * classes are not expected to manipulate this map.
     *
     * @return the map with the managed builders
     */
    protected ConcurrentMap<String, FileBasedConfigurationBuilder<T>> getManagedBuilders()
    {
        return managedBuilders;
    }

    /**
     * Registers event listeners at the passed in newly created managed builder.
     * This method registers a special {@code BuilderListener} which propagates
     * builder events to listeners registered at this builder. In addition,
     * {@code ConfigurationListener} and {@code ConfigurationErrorListener}
     * objects are registered at the new builder.
     *
     * @param newBuilder the builder to be initialized
     */
    private void initListeners(FileBasedConfigurationBuilder<T> newBuilder)
    {
        copyEventListeners(newBuilder);
        newBuilder.addBuilderListener(managedBuilderDelegationListener);
    }

    /**
     * Generates a file name for a managed builder based on the file name
     * pattern. This method prevents infinite loops which could happen if the
     * file name pattern cannot be resolved and the
     * {@code ConfigurationInterpolator} used by this object causes a recursive
     * lookup to this builder's configuration.
     *
     * @param multiParams the current builder parameters
     * @return the file name for a managed builder
     */
    private String fetchFileName(MultiFileBuilderParametersImpl multiParams)
    {
        String fileName;
        Boolean reentrant = inInterpolation.get();
        if (reentrant != null && reentrant.booleanValue())
        {
            fileName = multiParams.getFilePattern();
        }
        else
        {
            inInterpolation.set(Boolean.TRUE);
            try
            {
                fileName = constructFileName(multiParams);
            }
            finally
            {
                inInterpolation.set(Boolean.FALSE);
            }
        }
        return fileName;
    }

    /**
     * Creates a map with parameters for a new managed configuration builder.
     * This method merges the basic parameters set for this builder with the
     * specific parameters object for managed builders (if provided).
     *
     * @param params the parameters of this builder
     * @param multiParams the parameters object for this builder
     * @return the parameters for a new managed builder
     */
    private static Map<String, Object> createManagedBuilderParameters(
            Map<String, Object> params,
            MultiFileBuilderParametersImpl multiParams)
    {
        Map<String, Object> newParams = new HashMap<String, Object>(params);
        newParams.remove(KEY_INTERPOLATOR);
        BuilderParameters managedBuilderParameters =
                multiParams.getManagedBuilderParameters();
        if (managedBuilderParameters != null)
        {
            // clone parameters as they are applied to multiple builders
            BuilderParameters copy =
                    (BuilderParameters) ConfigurationUtils
                            .cloneIfPossible(managedBuilderParameters);
            newParams.putAll(copy.getParameters());
        }
        return newParams;
    }
}
