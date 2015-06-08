package bacnet;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.vertx.java.core.Handler;

public class BacnetLink {
	
	private Node node;
	
	private BacnetLink(Node node) {
		this.node = node;
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
		node.createChild("add connection").setAction(act).build().setSerializable(false);
		
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

			BacnetConn conn = new BacnetConn(child);
			conn.init();
		}
	}
	

}
