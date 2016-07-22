package bacnet.properties;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.Real;
import com.serotonin.bacnet4j.type.primitive.SignedInteger;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;

import bacnet.DataType;
import bacnet.LocalBacnetPoint;
import bacnet.Utils;

public class LocalPresentValueProperty extends LocalBacnetProperty {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(LocalUnitsProperty.class);
	}

	public static final int DEFAULT_ROOM_TEMPERATURE = 68;
	static final String ATTRIBUTE_EVENT_STATE = "event state";
	static final String ACTION_EDIT = "edit";

//	EventState state;

	public LocalPresentValueProperty(LocalBacnetPoint point, Node parent, Node node) {
		super(point, parent, node);

	}

	public LocalPresentValueProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent,
			Node node) {
		super(oid, pid, point, parent, node);

		Value v = new Value(DEFAULT_ROOM_TEMPERATURE); 
		node.setValue(new Value(String.valueOf(DEFAULT_ROOM_TEMPERATURE)));
		
		Encodable enc = Utils.valueToEncodable(DataType.NUMERIC, v, objectId.getObjectType(),
				PropertyIdentifier.presentValue, null);
		bacnetObj.writeProperty(PropertyIdentifier.presentValue, enc);

	}
	
	public void updatePropertyValue(Encodable enc){

		if ( null != enc){
			Value val;
			if (enc instanceof com.serotonin.bacnet4j.type.primitive.Boolean) {
				val = new Value(((com.serotonin.bacnet4j.type.primitive.Boolean) enc).booleanValue());
			} else if (enc instanceof Real) {
				val = new Value(((Real) enc).floatValue());
			} else if (enc instanceof UnsignedInteger) {
				val = new Value(((UnsignedInteger) enc).bigIntegerValue());
			} else if (enc instanceof SignedInteger) {
				val = new Value(((SignedInteger) enc).bigIntegerValue());
			} else {
				val = new Value(enc.toString());
			}

			node.setValue(new Value(String.valueOf(val)));
		}

	}
}
