import com.google.gson.Gson;
import io.activej.config.Config;
import io.activej.config.ConfigModule;
import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpServer;
import io.activej.http.RoutingServlet;
import io.activej.http.StaticServlet;
import io.activej.http.loader.IStaticLoader;
import io.activej.inject.annotation.Inject;
import io.activej.inject.annotation.Provides;
import io.activej.inject.module.Module;
import io.activej.launcher.Launcher;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.service.ServiceGraphModule;
import io.activej.uikernel.UiKernelServlets;

import java.util.concurrent.Executor;

import static io.activej.config.converter.ConfigConverters.ofInteger;
import static io.activej.config.converter.ConfigConverters.ofString;
import static io.activej.inject.module.Modules.combine;
import static java.util.concurrent.Executors.newSingleThreadExecutor;

public class WebappLauncher extends Launcher {
	private static final int DEFAULT_PORT = 8080;
	private static final String DEFAULT_PATH_TO_RESOURCES = "/static";

	@Inject
	HttpServer server;

	@Provides
	Gson gson() {
		return new Gson();
	}

	@Provides
	Config config() {
		return Config.ofClassPathProperties("configs.properties");
	}

	@Provides
	NioReactor reactor() {
		return Eventloop.create();
	}

	@Provides
	Executor executor() {
		return newSingleThreadExecutor();
	}

	@Provides
	IStaticLoader staticLoader(Reactor reactor, Executor executor, Config config) {
		return IStaticLoader.ofClassPath(reactor, executor, config.get(ofString(), "resources", DEFAULT_PATH_TO_RESOURCES));
	}

	@Provides
	AsyncServlet servlet(Reactor reactor, IStaticLoader staticLoader, Gson gson, PersonGridModel model, Config config) {
		StaticServlet staticServlet = StaticServlet.builder(reactor, staticLoader)
			.withIndexHtml()
			.build();
		AsyncServlet usersApiServlet = UiKernelServlets.apiServlet(reactor, model, gson);

		return RoutingServlet.builder(reactor)
			.with("/*", staticServlet)              // serves request if no other servlet matches
			.with("/api/users/*", usersApiServlet)  // our rest crud servlet that would serve the grid
			.build();
	}

	@Provides
	HttpServer server(NioReactor reactor, Config config, AsyncServlet servlet) {
		return HttpServer.builder(reactor, servlet)
			.withListenPort(config.get(ofInteger(), "port", DEFAULT_PORT))
			.build();
	}

	@Override
	protected Module getModule() {
		return combine(
			ServiceGraphModule.create(),
			ConfigModule.builder()
				.withEffectiveConfigLogger()
				.build());
	}

	@Override
	protected void run() throws Exception {
		awaitShutdown();
	}

	public static void main(String[] args) throws Exception {
		WebappLauncher launcher = new WebappLauncher();
		launcher.launch(args);
	}
}
