xquery version "3.0";

module namespace app="http://exist-db.org/extension/jms/templates";

import module namespace templates="http://exist-db.org/xquery/templates" ;
import module namespace config="http://exist-db.org/extension/jms/config" at "config.xqm";
import module namespace jms="http://exist-db.org/xquery/messaging" at "java:org.exist.messaging.xquery.MessagingModule";
                        
(:~
 : This is a sample templating function. It will be called by the templating module if
 : it encounters an HTML element with an attribute: data-template="app:test" or class="app:test" (deprecated). 
 : The function has to take 2 default parameters. Additional parameters are automatically mapped to
 : any matching request or function parameter.
 : 
 : @param $node the HTML node with the attribute which triggered this call
 : @param $model a map containing arbitrary data - used to pass information between template calls
 :)
declare function app:test($node as node(), $model as map(*)) {
    <p>Dummy template output generated by function app:test at {current-dateTime()}. The templating
        function was triggered by the class attribute <code>class="app:test"</code>.</p>
};

declare function app:executeAction($node as node(), $model as map(*), $action as xs:string, $receiver as xs:int) {
    
    <div>{ 
        
        try {
        switch ($action) 
           case "start" return jms:start($receiver)
           case "stop"  return jms:stop($receiver)
           case "close" return jms:close($receiver)
           default return "unknown action"
        } catch * {
            <div class="alert alert-error">
            {$err:description}
            </div> 
        }
        
        }</div>
};


declare function app:showReport($node as node(), $model as map(*), $show as xs:string, $receiver as xs:int) {
    
    <div>{ 
        
        let $report := jms:report($receiver)
        return 
            <div>
                
            <table id="manageTable" class="table table-striped table-hoover table-bordered table-condensed tablesorter table-scrollable">
            <caption>JMS configuration</caption>
                <thead>
                    <tr>
                    <th>Item</th><th>Value</th>
                    </tr>
                </thead>
            <tbody>
            
                <tr><th>id</th><td>{data($report/@id)}</td></tr>
                <tr><th>state</th><td>{data($report/state)}</td></tr>
                <tr><th>java.naming.factory.initial</th><td>{data($report/java.naming.factory.initial)}</td></tr>
                <tr><th>java.naming.provider.url</th><td>{data($report/java.naming.provider.url)}</td></tr>
                <tr><th>connection-factory</th><td>{data($report/connection-factory)}</td></tr>
                <tr><th>connection.username</th><td>{data($report/connection.username)}</td></tr>

                <tr><th>destination</th><td>{data($report/destination)}</td></tr>
                <tr><th>connection.client-id</th><td>{data($report/connection.client-id)}</td></tr>
                <tr><th>consumer.message-selector</th><td>{data($report/consumer.message-selector)}</td></tr>
                
                <tr><th>usage</th><td>{data($report/usage)}</td></tr>
</tbody>

                
            </table>
            <p/>
                <table id="manageTable" class="table table-striped table-hoover table-bordered table-condensed tablesorter table-scrollable">
                <caption>Statistics</caption>
                <thead>
                    <tr>
                        <th>Item</th><th>Value</th>
                    </tr>
                </thead>
                <tbody>
                    <tr><th>nrProcessedMessages</th><td>{data($report/statistics/nrProcessedMessages)}</td></tr>
                    <tr><th>nrUnprocessedMessages</th><td>{data($report/statistics/nrUnprocessedMessages)}</td></tr>
                    <tr><th>cumulativeProcessingTime</th><td>{data($report/statistics/cumulativeProcessingTime)}</td></tr>
                </tbody>
                </table>
            <p/>
            
            <table id="manageTable" class="table table-striped table-hoover table-bordered table-condensed tablesorter table-scrollable">
            <caption>Errors</caption>
                <thead>
                    <tr>
                        <th>Source</th><th>Message</th>
                    </tr>
                </thead>
                <tbody>
                    {
                        for $error in $report/errorMessages/error
                        return
                            <tr><th>{data($error/@src)}</th><td>{data($error)}</td></tr>
                    }

                </tbody>
                </table>
                
                
                 
        </div>
        }</div>
};

declare function app:showReceivers($node as node(), $model as map(*)) {
    
    try {

        <table id="manageTable" class="table table-striped table-hoover table-bordered table-condensed tablesorter table-scrollable">
        <thead>
            <tr>
                <th>Id</th><th>State</th><th>Action</th><th>URL</th><th>Destination</th><th>Client-id</th><th>P</th><th>F</th><th>E</th>
            </tr>
        </thead>
        <tbody>
        {                    
            for $id in jms:list()
            let $report := jms:report($id)
            let $nrErrors := count($report/errorMessages/error)
            return
                <tr>
                <td>{data($report/@id)}</td>
                <td>{data($report/state)}</td>
                <td>
                    <a title="Show Report" href="?show=report&amp;receiver={$id}"><i class="icon-info-sign"/></a>
                    <a title="Start Receiver" href="?action=start&amp;receiver={$id}"><i class="icon-play"/></a> 
                    <a title="Stop Receiver" href="?action=stop&amp;receiver={$id}"><i class="icon-pause"/></a>  
                    <a title="Close Receiver" href="?action=close&amp;receiver={$id}"><i class="icon-ban-circle"/></a>  
                    
                        
                </td>
                <td>{data($report/java.naming.provider.url)}</td>
                <td>{data($report/destination)}</td>
                <td>{data($report/connection.client-id)}</td>
                <td>{data($report/statistics/nrProcessedMessages)}</td>
                <td>{data($report/statistics/nrUnprocessedMessages)}</td>
                
                <td style="{ if($nrErrors eq 0) then '' else  'background-color:#f2dede;'}">{ 
    
                    if($nrErrors eq 0) 
                    then
                       $nrErrors 
                    else
                        <a id="error" href="#" data-html="true" data-toggle="tooltip" title="{data($report/errorMessages/error)}">{$nrErrors}</a>
                }</td>
                </tr>
        } 
        </tbody>
        </table>
        
    } catch * {
            <div class="alert alert-error">
            {$err:description}
            </div> 
    }

};