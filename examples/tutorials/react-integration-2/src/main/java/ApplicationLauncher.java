import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonWriter;
import com.dslplatform.json.runtime.Settings;
import io.activej.bytebuf.ByteBuf;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpResponse;
import io.activej.http.RoutingServlet;
import io.activej.http.StaticServlet;
import io.activej.http.loader.IStaticLoader;
import io.activej.inject.annotation.Provides;
import io.activej.launchers.http.HttpServerLauncher;
import io.activej.reactor.Reactor;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;

import static io.activej.http.HttpMethod.GET;
import static io.activej.http.HttpMethod.POST;
import static java.lang.Integer.parseInt;
import static java.util.concurrent.Executors.newSingleThreadExecutor;

//[START REGION_1]
public final class ApplicationLauncher extends HttpServerLauncher {
	@Provides
	RecordDAO recordRepo() {
		return new RecordImplDAO();
	}

	@Provides
	Executor executor() {
		return newSingleThreadExecutor();
	}

	@Provides
	DslJson<?> dslJson() {
		return new DslJson<>(Settings.withRuntime());
	}
	//[END REGION_1]

	//[START REGION_2]
	@Provides
	IStaticLoader staticLoader(Reactor reactor, Executor executor) {
		return IStaticLoader.ofClassPath(reactor, executor, "build/");
	}

	@Provides
	AsyncServlet servlet(Reactor reactor, IStaticLoader staticLoader, RecordDAO recordDAO, DslJson<?> dslJson) {
		return RoutingServlet.builder(reactor)
			.with("/*", StaticServlet.builder(reactor, staticLoader)
				.withIndexHtml()
				.build())
			//[END REGION_2]
			//[START REGION_3]
			.with(POST, "/add", request -> request.loadBody()
				.then($ -> {
					ByteBuf body = request.getBody();
					try {
						byte[] bodyBytes = body.getArray();
						Record record = dslJson.deserialize(Record.class, bodyBytes, bodyBytes.length);
						recordDAO.add(record);
						return HttpResponse.ok200().toPromise();
					} catch (IOException e) {
						return HttpResponse.ofCode(400).toPromise();
					}
				}))
			.with(GET, "/get/all", request -> {
				Map<Integer, Record> records = recordDAO.findAll();
				JsonWriter writer = dslJson.newWriter();
				try {
					dslJson.serialize(writer, records);
				} catch (IOException e) {
					throw new AssertionError(e);
				}
				return HttpResponse.ok200()
					.withJson(writer.toString())
					.toPromise();
			})
			//[START REGION_4]
			.with(GET, "/delete/:recordId", request -> {
				int id = parseInt(request.getPathParameter("recordId"));
				recordDAO.delete(id);
				return HttpResponse.ok200().toPromise();
			})
			//[END REGION_4]
			.with(GET, "/toggle/:recordId/:planId", request -> {
				int id = parseInt(request.getPathParameter("recordId"));
				int planId = parseInt(request.getPathParameter("planId"));

				Record record = recordDAO.find(id);
				Plan plan = record.getPlans().get(planId);
				plan.toggle();
				return HttpResponse.ok200().toPromise();
			})
			.build();
		//[END REGION_3]
	}

	//[START REGION_5]
	public static void main(String[] args) throws Exception {
		ApplicationLauncher launcher = new ApplicationLauncher();
		launcher.launch(args);
	}
	//[END REGION_5]
}
