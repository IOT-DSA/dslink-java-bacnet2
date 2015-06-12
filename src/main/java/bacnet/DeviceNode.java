package bacnet;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonObject;

import bacnet.BacnetConn.CovType;

import com.serotonin.bacnet4j.RemoteDevice;

public class DeviceNode extends DeviceFolder {
	
	RemoteDevice device;
	long interval;
	CovType covType;
	
	DeviceNode(BacnetConn conn, Node node, RemoteDevice d) {
		super(conn, node);
		this.device = d;
		this.root = this;
		
		this.interval = node.getAttribute("refresh interval").getNumber().longValue();
		this.covType = CovType.NONE;
		try {
			this.covType = CovType.valueOf(node.getAttribute("cov usage").getString());
		} catch (Exception e) {
		}
		
		makeEditAction();
	}
	
	private void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
		act.addParameter(new Parameter("MAC address", ValueType.STRING, node.getAttribute("MAC address")));
	    act.addParameter(new Parameter("refresh interval", ValueType.NUMBER, node.getAttribute("refresh interval")));
	    act.addParameter(new Parameter("cov usage", ValueType.makeEnum("NONE", "UNCONFIRMED", "CONFIRMED"), node.getAttribute("cov usage")));
	    act.addParameter(new Parameter("cov lease time (minutes)", ValueType.NUMBER, node.getAttribute("cov lease time (minutes)")));
	    node.createChild("edit").setAction(act).build().setSerializable(false);
	}
	
	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			long interv = event.getParameter("refresh interval", ValueType.NUMBER).getNumber().longValue();
			CovType covtype = CovType.NONE;
			try {
				covtype = CovType.valueOf(event.getParameter("cov usage").getString());
			} catch (Exception e1) {
			}
			int covlife =event.getParameter("cov lease time (minutes)", ValueType.NUMBER).getNumber().intValue();
			String mac = event.getParameter("MAC address", ValueType.STRING).getString();
			final RemoteDevice d = conn.getDevice(mac, interv, covtype, covlife);
			conn.getDeviceProps(d);
			interval = interv;
			covType = covtype;
	        node.setAttribute("MAC address", new Value(d.getAddress().getMacAddress().toIpPortString()));
	        node.setAttribute("refresh interval", new Value(interval));
	        node.setAttribute("cov usage", new Value(covtype.toString()));
	        node.setAttribute("cov lease time (minutes)", new Value(covlife));
	        
	        if (!name.equals(node.getName())) {
				rename(name);
			}
	        
	        node.removeChild("edit");
	        makeEditAction();
		}
	}
	
	@Override
	protected void duplicate(String name) {
		JsonObject jobj = conn.link.copySerializer.serialize();
		JsonObject parentobj = getParentJson(jobj, node);
		JsonObject nodeobj = parentobj.getObject(node.getName());
		parentobj.putObject(name, nodeobj);
		conn.link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name);
		conn.restoreDevice(newnode);
		return;
		
	}
	
	protected JsonObject getParentJson(JsonObject jobj, Node n) {
		return jobj.getObject("BACNET").getObject(conn.node.getName());
	}
	
}
