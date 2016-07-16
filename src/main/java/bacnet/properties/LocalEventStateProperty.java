package bacnet.properties;

import java.util.Arrays;

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

import com.serotonin.bacnet4j.type.enumerated.EventState;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

import bacnet.LocalBacnetPoint;


public class LocalEventStateProperty extends LocalBacnetProperty{
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(LocalUnitsProperty.class);
	}

	static final String ATTRIBUTE_EVENT_STATE = "event state";
	static final String ACTION_EDIT = "edit";

	EventState state;

	public LocalEventStateProperty(LocalBacnetPoint point, Node parent, Node node) {
		super(point, parent, node);

	}
	
	public LocalEventStateProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent, Node node){
		super(oid, pid, point, parent, node);
		
		bacnetObj.writeProperty(PropertyIdentifier.eventState, EventState.normal);
		makeEditAction();
	}

	private  String[] enumeratedNames(){
		String valuesStr = Arrays.toString(EventState.ALL);
		return valuesStr.substring(1, valuesStr.length() - 1).replace(" ", "").split(",");
	}
	
	protected void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(
				new Parameter(ATTRIBUTE_EVENT_STATE, ValueType.makeEnum(enumeratedNames()),
						node.getAttribute(ATTRIBUTE_EVENT_STATE)));

		Node editNode = node.getChild(ACTION_EDIT);
		if (editNode == null)
			node.createChild(ACTION_EDIT).setAction(act).build().setSerializable(false);
		else
			editNode.setAction(act);
	}

	private class EditHandler implements Handler<ActionResult> {

		@Override
		public void handle(ActionResult event) {
			state = parseEventState(
					event.getParameter(ATTRIBUTE_EVENT_STATE, ValueType.STRING).getString());
			
			bacnetObj.writeProperty(PropertyIdentifier.eventState, state);
			
			Value newVal = new Value(state.toString());
			node.setAttribute(ATTRIBUTE_EVENT_STATE, newVal);
			node.setValue(newVal);
		}
	}

	protected EventState parseEventState(String eventString) {

		for (EventState state : EventState.ALL){
			if(state.toString().equals(eventString)){
				return state;
			}
		}

		return null;
	}
}
