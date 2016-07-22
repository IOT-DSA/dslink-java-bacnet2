package bacnet.properties;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;

import bacnet.LocalBacnetPoint;

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.BinaryPV;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

public class LocalBooleanProperty extends LocalBacnetProperty {

	boolean state;
	
	public LocalBooleanProperty(LocalBacnetPoint point, Node parent, Node node) {
		super(point, parent, node);

	}
	
	public LocalBooleanProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent, Node node){
		super(oid, pid, point, parent, node);
		
		bacnetObj.writeProperty(pid, com.serotonin.bacnet4j.type.primitive.Boolean.FALSE);
		
		node.setValueType(ValueType.BOOL);
		node.setValue(new Value(false));
		node.setWritable(Writable.WRITE);
		node.getListener().setValueHandler(new SetHandler());
	}
	
	private class SetHandler implements Handler<ValuePair> {

		@Override
		public void handle(ValuePair event) {
			if (!event.isFromExternalSource()) return;
			Value newVal = event.getCurrent();
			state = newVal.getBool();
			
			bacnetObj.writeProperty(propertyId, new com.serotonin.bacnet4j.type.primitive.Boolean(state));		
			node.setAttribute(propertyId.toString(), newVal);
		}
	}
	
	@Override
	public void updatePropertyValue(Encodable enc) {
		if (enc instanceof com.serotonin.bacnet4j.type.primitive.Boolean) {
			state = ((com.serotonin.bacnet4j.type.primitive.Boolean) enc).booleanValue();
			node.setValue(new Value(state));
		}

	}

}
