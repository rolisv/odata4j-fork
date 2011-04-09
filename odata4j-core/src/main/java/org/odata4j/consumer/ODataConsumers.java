package org.odata4j.consumer;

import org.odata4j.core.OClientBehavior;
import org.odata4j.core.OClientBehaviors;

public class ODataConsumers {

    private ODataConsumers() {
    }

    public static ODataConsumer azureTables(String account, String key) {
        String url = "http://" + account + ".table.core.windows.net/";

        return ODataConsumer.create(url, OClientBehaviors.azureTables(account, key));

    }

    public static ODataConsumer dallas(String serviceRootUri, String accountKey, String uniqueUserId) {
        
        // CTP2
        //OClientBehavior dallasAuth = new DallasCtp2AuthenticationBehavior(accountKey, uniqueUserId);
        //OClientBehavior paging = new OldStylePagingBehavior(50, 1);
        //return ODataConsumer.create(serviceRootUri, dallasAuth, paging);
        
        // CTP3
        OClientBehavior basicAuth = OClientBehaviors.basicAuth("accountKey", accountKey);
        return ODataConsumer.create(serviceRootUri, basicAuth);
    }
    
    public static ODataConsumer dataMarket(String serviceRootUri, String accountKey) {
        OClientBehavior basicAuth = OClientBehaviors.basicAuth("accountKey", accountKey);
        return ODataConsumer.create(serviceRootUri, basicAuth);
    }
    
    
}
