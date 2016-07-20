package bacnet.properties;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;

import bacnet.LocalBacnetPoint;

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

public class LocalCharacterStringProperty extends LocalBacnetProperty {
	public LocalCharacterStringProperty(LocalBacnetPoint point, Node parent, Node node) {
		super(point, parent, node);

	}
	
	public LocalCharacterStringProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent, Node node){
		super(oid, pid, point, parent, node);
		
		bacnetObj.writeProperty(pid, new CharacterString(""));
		
		node.setValueType(ValueType.STRING);
		node.setValue(new Value(""));
		node.setWritable(Writable.WRITE);
		node.getListener().setValueHandler(new SetHandler());
	}
	
	private class SetHandler implements Handler<ValuePair> {

		@Override
		public void handle(ValuePair event) {
			if (!event.isFromExternalSource()) return;
			Value newVal = event.getCurrent();
			set(newVal);
			
		}
	}
	
	public void set(Value newVal) {
		String str = newVal.getString();
		
		bacnetObj.writeProperty(propertyId, new CharacterString(str));
		node.setAttribute(propertyId.toString(), newVal);
	}

	@Override
	public void updatePropertyValue(Encodable enc) {
		if (enc instanceof CharacterString) {
			node.setValue(new Value(((CharacterString) enc).getValue()));
		}
	}

}
