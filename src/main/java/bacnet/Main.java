package bacnet;

import org.dsa.iot.dslink.DSLink;
import org.dsa.iot.dslink.DSLinkFactory;
import org.dsa.iot.dslink.DSLinkHandler;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeBuilder;
import org.dsa.iot.dslink.node.NodeManager;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.historian.stats.GetHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main extends DSLinkHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

	public static void main(String[] args) {
		DSLinkFactory.start(args, new Main());
		
//		Reflections reflections = new Reflections("com.serotonin.bacnet4j");
//		Set<Class<? extends BaseType>> constructedTypes = reflections.getSubTypesOf(BaseType.class);
//		Set<Class<?>> params = new HashSet<Class<?>>();
//		for (Class<? extends BaseType> clazz: constructedTypes) {
//			for (Constructor<?> constr: clazz.getConstructors()) {
//				for (Class<?> cz: constr.getParameterTypes()) {
//					if (!Encodable.class.isAssignableFrom(cz) && !cz.isPrimitive() && 
//							!cz.equals(ByteQueue.class) && !cz.equals(ChoiceOptions.class) && !cz.equals(String.class)) {
//						System.out.println(cz.toString() + "\t" + clazz.toString());
//					}
//				}
//			}
//		}
//		for (Class<?> cz: params) {
//			System.out.println(cz.toString());
//		}
		
	}

	@Override
	public boolean isResponder() {
		return true;
	}

	@Override
	public void onResponderInitialized(DSLink link) {
			LOGGER.info("Initialized");
			NodeManager manager = link.getNodeManager();
			Node superRoot = manager.getNode("/").getNode();
			
			NodeBuilder b = superRoot.createChild("defs", true);
			b.setSerializable(false);
			b.setHidden(true);
			Node node = b.build();

			b = node.createChild("profile", true);
			node = b.build();

			b = node.createChild("getHistory_", true);
			Action act = new Action(Permission.READ, null);
			GetHistory.initProfile(act);
			b.setAction(act);
			b.build();
			
			BacnetLink.start(superRoot);
	}

	@Override
	public void onResponderConnected(DSLink link) {
		LOGGER.info("Connected");
	}

}
