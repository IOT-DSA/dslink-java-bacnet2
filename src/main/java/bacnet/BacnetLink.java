package bacnet;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.Objects;
import org.vertx.java.core.Handler;

import com.serotonin.bacnet4j.event.DeviceEventAdapter;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.util.PropertyReferences;

public class BacnetLink {
	
	private Node node;
	private final Map<Node, ScheduledFuture<?>> futures;
	
	private BacnetLink(Node node) {
		this.node = node;
		this.futures = new ConcurrentHashMap<Node, ScheduledFuture<?>>();
	}
	
	public static void start(Node parent) {
		Node node = parent.createChild("BACNET").build();
		node.setSerializable(false);
		final BacnetLink link = new BacnetLink(node);
		link.init();
	}
	
	private void init() {
		
		Action act = new Action(Permission.READ, new AddConnHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("broadcast ip", ValueType.STRING, new Value("10.0.1.255")));
		act.addParameter(new Parameter("port", ValueType.NUMBER, new Value(47808)));
		act.addParameter(new Parameter("local bind address", ValueType.STRING, new Value("0.0.0.0")));
		act.addParameter(new Parameter("local network number", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("timeout", ValueType.NUMBER, new Value(6000)));
		act.addParameter(new Parameter("segment timeout", ValueType.NUMBER, new Value(5000)));
		act.addParameter(new Parameter("segment window", ValueType.NUMBER, new Value(5)));
		act.addParameter(new Parameter("retries", ValueType.NUMBER, new Value(2)));
		act.addParameter(new Parameter("local device id", ValueType.NUMBER, new Value(1212)));
		act.addParameter(new Parameter("local device name", ValueType.STRING, new Value("DSLink")));
		act.addParameter(new Parameter("local device vendor", ValueType.STRING, new Value("DGLogik Inc.")));
		act.addParameter(new Parameter("default refresh interval", ValueType.NUMBER, new Value(5)));
		node.createChild("add connection").setAction(act).build().setSerializable(false);
		
	}
	
	DeviceEventAdapter setupPoint(final BacnetPoint point, final DeviceFolder devicefold) {
		Node child = point.node.getChild("presentValue");
		if (point.isCov()) {
			child.getListener().setOnSubscribeHandler(null);
			ScheduledFuture<?> fut = futures.remove(child);
			if (fut != null) {
				fut.cancel(false);
			}
			child.getListener().setOnUnsubscribeHandler(null);
			getPoint(point, devicefold);
			DeviceEventAdapter cl = devicefold.getNewCovListener(point);
			devicefold.setupCov(point, cl);
			return cl;
		}
		child.getListener().setOnSubscribeHandler(new Handler<Node>() {
			public void handle(final Node event) {
				if (futures.containsKey(event)) {
					return;
		        }
				ScheduledThreadPoolExecutor stpe = Objects.getDaemonThreadPool();
				ScheduledFuture<?> fut = stpe.scheduleWithFixedDelay(new Runnable() {
					public void run() {
						getPoint(point, devicefold);
					}	                 
				}, 0, devicefold.root.interval, TimeUnit.SECONDS);
				futures.put(event, fut);
			}
		});

		child.getListener().setOnUnsubscribeHandler(new Handler<Node>() {
			public void handle(Node event) {
				ScheduledFuture<?> fut = futures.remove(event);
				if (fut != null) {
					fut.cancel(false);
				}
			}
		});
		return null;
    }
	
	private void getPoint(BacnetPoint point, DeviceFolder devicefold) {
		PropertyReferences refs = new PropertyReferences();
		Map<ObjectIdentifier, BacnetPoint> points = new HashMap<ObjectIdentifier, BacnetPoint>();
		ObjectIdentifier oid = point.oid;
      	DeviceFolder.addPropertyReferences(refs, oid);
      	points.put(oid, point);
      	devicefold.getProperties(refs, points);
	}

	
	private class AddConnHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			String bip = event.getParameter("broadcast ip", ValueType.STRING).getString();
			int port = event.getParameter("port", ValueType.NUMBER).getNumber().intValue();
			String lba = event.getParameter("local bind address", ValueType.STRING).getString();
			int lnn = event.getParameter("local network number", ValueType.NUMBER).getNumber().intValue();
			int timeout = event.getParameter("timeout", ValueType.NUMBER).getNumber().intValue();
			int segtimeout = event.getParameter("segment timeout", ValueType.NUMBER).getNumber().intValue();
			int segwin = event.getParameter("segment window", ValueType.NUMBER).getNumber().intValue();
			int retries = event.getParameter("retries", ValueType.NUMBER).getNumber().intValue();
			int locdevId = event.getParameter("local device id", ValueType.NUMBER).getNumber().intValue();
			String locdevName = event.getParameter("local device name", ValueType.STRING).getString();
			String locdevVend = event.getParameter("local device vendor", ValueType.STRING).getString();
			long interval = event.getParameter("default refresh interval", ValueType.NUMBER).getNumber().longValue();
			
			Node child = node.createChild(name).build();
			child.setAttribute("broadcast ip", new Value(bip));
			child.setAttribute("port", new Value(port));
			child.setAttribute("local bind address", new Value(lba));
			child.setAttribute("local network number", new Value(lnn));
			child.setAttribute("timeout", new Value(timeout));
			child.setAttribute("segment timeout", new Value(segtimeout));
			child.setAttribute("segment window", new Value(segwin));
			child.setAttribute("retries", new Value(retries));
			child.setAttribute("local device id", new Value(locdevId));
			child.setAttribute("local device name", new Value(locdevName));
			child.setAttribute("local device vendor", new Value(locdevVend));
			child.setAttribute("default refresh interval", new Value(interval));

			BacnetConn conn = new BacnetConn(getMe(), child);
			conn.init();
		}
	}
	
	private BacnetLink getMe() {
		return this;
	}
	

}
