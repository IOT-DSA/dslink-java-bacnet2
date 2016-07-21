package bacnet;

import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;

import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.json.JsonArray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.LocalDevice;

import bacnet.BacnetConn.CovType;

public class LocalDeviceNode extends LocalDeviceFolder {

	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(LocalDeviceNode.class);
	}

	static final String NODE_EVENTS = "EVENTS";
	static final String NODE_STATUS = "STATUS";

	static final String STATUS_SETTINGUP = "Setting up connection";
	static final String STATUS_CONNECTED = "Connected";
	static final String STATUS_STOPPED = "Stopped";
	static final String STATUS_DISABLED = "disabled";
	static final String STATUS_ENABLED = "enabled";
	static final String STATUS_NOT_CONNECTED = "not connected";
	static final String STATUS_NO_COM_PORT = "COM Port not found";
	static final String STATUS_INITIALIZING_FAILED = "Error initializing local device";

	static final String ACTION_DISABLE = "disable";
	static final String ACTION_ENABLE = "enable";
	static final String ACTION_EDIT = "edit";
	static final String ACTION_STOP = "stop";
	static final String ACTION_RESTART = "restart";

	final Node statNode;


	final Node eventNode;
	LocalDevice localDevice;
	CovType covType;
	boolean enabled;
	long interval;

	public LocalDeviceNode(BacnetConn conn, Node node, LocalDevice device) {
		super(conn, node);

		this.localDevice = device;
		this.root = this;

		if (node.getChild(NODE_STATUS) != null) {
			this.statNode = node.getChild(NODE_STATUS);
			enabled = new Value(STATUS_ENABLED).equals(statNode.getValue());
		} else {
			this.statNode = node.createChild(NODE_STATUS).setValueType(ValueType.STRING)
					.setValue(new Value(STATUS_ENABLED)).build();
			enabled = true;
		}

		if (node.getChild(NODE_EVENTS) != null) {
			this.eventNode = node.getChild(NODE_EVENTS);
		} else {
			this.eventNode = node.createChild(NODE_EVENTS).setValueType(ValueType.ARRAY)
					.setValue(new Value(new JsonArray())).build();
		}

		if (device == null && !STATUS_DISABLED.equals(statNode.getValue().getString())) {
			statNode.setValue(new Value(STATUS_NOT_CONNECTED));
			enabled = false;
		}

		if (!STATUS_DISABLED.equals(statNode.getValue().getString())) {
			Action act = new Action(Permission.READ, new Handler<ActionResult>() {
				public void handle(ActionResult event) {
					// disable(true);
				}
			});
			node.createChild(ACTION_DISABLE).setAction(act).build().setSerializable(false);

		}
		if (!STATUS_ENABLED.equals(statNode.getValue().getString())) {
			Action act = new Action(Permission.READ, new Handler<ActionResult>() {
				public void handle(ActionResult event) {
					// enable(true);
				}
			});
			node.createChild(ACTION_ENABLE).setAction(act).build().setSerializable(false);
		}

		makeEditAction();
	}

	private void rename(String name) {

	}

	@Override
	protected void remove() {
		super.remove();

	}

	protected LocalDevice getLocalDevice() {
		return conn.getLocalDevice();
	}

	private void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));

		Node editNode = node.getChild(ACTION_EDIT);
		if (editNode == null)
			node.createChild(ACTION_EDIT).setAction(act).build().setSerializable(false);
		else
			editNode.setAction(act);
	}

	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			if (!name.equals(node.getName())) {
				rename(name);
			}

			makeEditAction();
		}
	}
    
	@Override
	public Node getStatusNode() {
		return statNode;
	}
}
