package bacnet.properties;


import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.Boolean;

import bacnet.LocalBacnetPoint;


public class LocalOutOfServiceProperty extends LocalBacnetProperty{
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(LocalUnitsProperty.class);
	}

	static final String ATTRIBUTE_OUTOFSERVICE = "outOfService";
	static final String ACTION_EDIT = "edit";

	Boolean outOfService;

	public LocalOutOfServiceProperty(LocalBacnetPoint point, Node parent, Node node) {
		super(point, parent, node);

	}
	
	public LocalOutOfServiceProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent, Node node){
		super(oid, pid, point, parent, node);
		
		bacnetObj.writeProperty(PropertyIdentifier.outOfService, new Boolean(false));
		makeEditAction();
	}
	
	protected void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(
				new Parameter(ATTRIBUTE_OUTOFSERVICE, ValueType.BOOL,
						node.getAttribute(ATTRIBUTE_OUTOFSERVICE)));

		Node editNode = node.getChild(ACTION_EDIT);
		if (editNode == null)
			node.createChild(ACTION_EDIT).setAction(act).build().setSerializable(false);
		else
			editNode.setAction(act);
	}

	private class EditHandler implements Handler<ActionResult> {

		@Override
		public void handle(ActionResult event) {
			outOfService = parseOutOfService(
					event.getParameter(ATTRIBUTE_OUTOFSERVICE, ValueType.BOOL).getBool());

			bacnetObj.writeProperty(PropertyIdentifier.outOfService, outOfService);

			Value newVal = new Value(outOfService.booleanValue());
			node.setAttribute(ATTRIBUTE_OUTOFSERVICE, newVal);
			node.setValue(new Value(outOfService.toString()));
		}
	}

	protected Boolean parseOutOfService(java.lang.Boolean outOfService) {
        if (outOfService){
        	return Boolean.TRUE;
        } else {
        	return Boolean.FALSE;
        }

	}
}
