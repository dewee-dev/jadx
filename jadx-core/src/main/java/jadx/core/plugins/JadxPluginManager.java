package jadx.core.plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JadxDecompiler;
import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.input.JadxCodeInput;
import jadx.api.plugins.loader.JadxPluginLoader;
import jadx.api.plugins.options.JadxPluginOptions;
import jadx.api.plugins.options.OptionDescription;
import jadx.core.plugins.versions.VerifyRequiredVersion;

public class JadxPluginManager {
	private static final Logger LOG = LoggerFactory.getLogger(JadxPluginManager.class);

	private final JadxDecompiler decompiler;
	private final JadxPluginsData pluginsData;
	private final Set<String> disabledPlugins;
	private final SortedSet<PluginContext> allPlugins = new TreeSet<>();
	private final SortedSet<PluginContext> resolvedPlugins = new TreeSet<>();
	private final Map<String, String> provideSuggestions = new TreeMap<>();

	private final List<Consumer<PluginContext>> addPluginListeners = new ArrayList<>();

	public JadxPluginManager(JadxDecompiler decompiler) {
		this.decompiler = decompiler;
		this.pluginsData = new JadxPluginsData(decompiler, this);
		this.disabledPlugins = decompiler.getArgs().getDisabledPlugins();
	}

	/**
	 * Add suggestion how to resolve conflicting plugins
	 */
	public void providesSuggestion(String provides, String pluginId) {
		provideSuggestions.put(provides, pluginId);
	}

	public void load(JadxPluginLoader pluginLoader) {
		allPlugins.clear();
		VerifyRequiredVersion verifyRequiredVersion = new VerifyRequiredVersion();
		for (JadxPlugin plugin : pluginLoader.load()) {
			addPlugin(plugin, verifyRequiredVersion);
		}
		resolve();
	}

	public void register(JadxPlugin plugin) {
		Objects.requireNonNull(plugin);
		PluginContext addedPlugin = addPlugin(plugin, new VerifyRequiredVersion());
		if (addedPlugin == null) {
			LOG.debug("Can't register plugin, it was disabled: {}", plugin.getPluginInfo().getPluginId());
			return;
		}
		LOG.debug("Register plugin: {}", addedPlugin.getPluginId());
		resolve();
	}

	private @Nullable PluginContext addPlugin(JadxPlugin plugin, VerifyRequiredVersion verifyRequiredVersion) {
		PluginContext pluginContext = new PluginContext(decompiler, pluginsData, plugin);
		if (disabledPlugins.contains(pluginContext.getPluginId())) {
			return null;
		}
		String requiredJadxVersion = pluginContext.getPluginInfo().getRequiredJadxVersion();
		if (!verifyRequiredVersion.isCompatible(requiredJadxVersion)) {
			LOG.warn("Plugin '{}' not loaded: requires '{}' jadx version which it is not compatible with current: {}",
					pluginContext, requiredJadxVersion, verifyRequiredVersion.getJadxVersion());
			return null;
		}
		LOG.debug("Loading plugin: {}", pluginContext);
		if (!allPlugins.add(pluginContext)) {
			throw new IllegalArgumentException("Duplicate plugin id: " + pluginContext + ", class " + plugin.getClass());
		}
		addPluginListeners.forEach(l -> l.accept(pluginContext));
		return pluginContext;
	}

	public boolean unload(String pluginId) {
		boolean result = allPlugins.removeIf(context -> {
			if (context.getPluginId().equals(pluginId)) {
				LOG.debug("Unload plugin: {}", pluginId);
				return true;
			}
			return false;
		});
		resolve();
		return result;
	}

	public SortedSet<PluginContext> getAllPluginContexts() {
		return allPlugins;
	}

	public SortedSet<PluginContext> getResolvedPluginContexts() {
		return resolvedPlugins;
	}

	private synchronized void resolve() {
		Map<String, List<PluginContext>> provides = allPlugins.stream()
				.collect(Collectors.groupingBy(p -> p.getPluginInfo().getProvides()));
		List<PluginContext> resolved = new ArrayList<>(provides.size());
		provides.forEach((provide, list) -> {
			if (list.size() == 1) {
				resolved.add(list.get(0));
			} else {
				String suggestion = provideSuggestions.get(provide);
				if (suggestion != null) {
					list.stream().filter(p -> p.getPluginId().equals(suggestion))
							.findFirst()
							.ifPresent(resolved::add);
				} else {
					PluginContext selected = list.get(0);
					resolved.add(selected);
					LOG.debug("Select providing '{}' plugin '{}', candidates: {}", provide, selected, list);
				}
			}
		});
		resolvedPlugins.clear();
		resolvedPlugins.addAll(resolved);
	}

	public void initAll() {
		init(allPlugins);
	}

	public void initResolved() {
		init(resolvedPlugins);
	}

	public void init(SortedSet<PluginContext> pluginContexts) {
		AppContext defAppContext = buildDefaultAppContext();
		for (PluginContext context : pluginContexts) {
			try {
				if (context.getAppContext() == null) {
					context.setAppContext(defAppContext);
				}
				context.init();
			} catch (Exception e) {
				LOG.warn("Failed to init plugin: {}", context.getPluginId(), e);
			}
		}
		for (PluginContext context : pluginContexts) {
			JadxPluginOptions options = context.getOptions();
			if (options != null) {
				verifyOptions(context, options);
			}
		}
	}

	public void unloadAll() {
		unload(allPlugins);
	}

	public void unloadResolved() {
		unload(resolvedPlugins);
	}

	public void unload(SortedSet<PluginContext> pluginContexts) {
		for (PluginContext context : pluginContexts) {
			try {
				context.unload();
			} catch (Exception e) {
				LOG.warn("Failed to unload plugin: {}", context.getPluginId(), e);
			}
		}
	}

	private AppContext buildDefaultAppContext() {
		AppContext appContext = new AppContext();
		appContext.setGuiContext(null);
		appContext.setFilesGetter(decompiler.getArgs().getFilesGetter());
		return appContext;
	}

	private void verifyOptions(PluginContext pluginContext, JadxPluginOptions options) {
		String pluginId = pluginContext.getPluginId();
		List<OptionDescription> descriptions = options.getOptionsDescriptions();
		if (descriptions == null) {
			throw new IllegalArgumentException("Null option descriptions in plugin id: " + pluginId);
		}
		String prefix = pluginId + '.';
		descriptions.forEach(descObj -> {
			String optName = descObj.name();
			if (optName == null || !optName.startsWith(prefix)) {
				throw new IllegalArgumentException("Plugin option name should start with plugin id: '" + prefix + "', option: " + optName);
			}
			String desc = descObj.description();
			if (desc == null || desc.isEmpty()) {
				throw new IllegalArgumentException("Plugin option description not set, plugin: " + pluginId);
			}
			List<String> values = descObj.values();
			if (values == null) {
				throw new IllegalArgumentException("Plugin option values is null, option: " + optName + ", plugin: " + pluginId);
			}
		});
	}

	public List<JadxCodeInput> getCodeInputs() {
		return getResolvedPluginContexts()
				.stream()
				.flatMap(p -> p.getCodeInputs().stream())
				.collect(Collectors.toList());
	}

	public void registerAddPluginListener(Consumer<PluginContext> listener) {
		this.addPluginListeners.add(listener);
		// run for already added plugins
		getAllPluginContexts().forEach(listener);
	}
}
