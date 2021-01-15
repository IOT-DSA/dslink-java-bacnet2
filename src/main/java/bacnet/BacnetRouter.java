package bacnet;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;

public class BacnetRouter {
	
	static final String ACTION_REMOVE = "remove";
	static final String ACTION_EDIT = "edit";
	
	final BacnetIpConn conn;
	final Node node;
	
	public BacnetRouter(BacnetIpConn conn, Node node) {
		this.conn = conn;
		this.node = node;
		
		makeRemoveAction();
		makeEditAction();
	}

	private void makeRemoveAction() {
		Action act = new Action(Permission.READ, event -> remove());
		Node anode = node.getChild(ACTION_REMOVE, true);
		if (anode == null) {
			node.createChild(ACTION_REMOVE, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void makeEditAction() {
		Action act = new Action(Permission.READ, event -> edit(event));
		act.addParameter(new Parameter("Network Number", ValueType.NUMBER, new Value(getNetworkNumber())));
		act.addParameter(new Parameter("IP", ValueType.STRING, new Value(getIp())));
		act.addParameter(new Parameter("Port", ValueType.NUMBER, new Value(getPort())));
		act.addParameter(new Parameter("Register as Foreign Device", ValueType.BOOL, new Value(shouldRegisterAsForeignDevice())));
		Node anode = node.getChild(ACTION_EDIT, true);
		if (anode == null) {
			node.createChild(ACTION_EDIT, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
		
	}
	
	protected void remove() {
		conn.removeRouter(node);
		node.delete(false);
	}
	
	protected void edit(ActionResult event) {
		conn.removeRouter(node);
		
		Utils.setConfigsFromActionResult(node, event);
		makeEditAction();
		
		conn.addRouter(node);
	}
	
	public int getNetworkNumber() {
		return node.getRoConfig("Network Number").getNumber().intValue();
	}
	
	public String getIp() {
		return node.getRoConfig("IP").getString();
	}
	
	public int getPort() {
		return node.getRoConfig("Port").getNumber().intValue();
	}
	
	public boolean shouldRegisterAsForeignDevice() {
		return node.getRoConfig("Register as Foreign Device").getBool();
	}


}
