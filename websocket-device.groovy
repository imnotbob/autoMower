/**
 *	Automower WebSocket  (Hubitat)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *	  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Modified July 24, 2022
 */

// NOTICE: This device will not work on SmartThings

//file:noinspection unused
//file:noinspection GroovySillyAssignment
//file:noinspection UnnecessaryQualifiedReference
//file:noinspection GroovyUnusedAssignment

static String getVersionNum()		{ return "00.00.04" }
static String getVersionLabel() 	{ return "Husqvarna AutoMower webSocket, version ${getVersionNum()}" }

import groovy.transform.Field
import groovy.json.*
import java.text.SimpleDateFormat

//************************************************
//*			   STATIC VARIABLES			   *
//************************************************
@Field static final String devVersionFLD = '0.0.3.0'
@Field static final String devModifiedFLD= '2022-07-15'
@Field static final String sNULL		 = (String)null
@Field static final String sBLANK		= ''
@Field static final String sSPACE		= ' '
@Field static final String sLINEBR		= '<br>'
@Field static final String sCLRRED		= 'red'
@Field static final String sCLRGRY		= 'gray'
@Field static final String sCLRORG		= 'orange'
@Field static final String sTRUE		= 'true'
@Field static final String sFALSE		= 'false'

//************************************************
//*		  IN-MEMORY ONLY VARIABLES			*
//* (Cleared only on HUB REBOOT or CODE UPDATES) *
//************************************************
@Field volatile static Map<String,Map> historyMapFLD= [:]
// @Field volatile static String gitBranchFLD= null

static String devVersion()  { return devVersionFLD }
static String devVersionDt(){ return devModifiedFLD }
static Boolean isWS()		{ return true }

metadata{
	definition (
			name:			"Husqvarna AutoMower WS",
			namespace:		"imnotbob",
			author:			"imnot_bob",
			importUrl:		"https://raw.githubusercontent.com/imnotbob/AutoMower/master/websocket-device.groovy"
	)
	{
		capability "Initialize"
		capability "Refresh"
		capability "Actuator"
	}
}

preferences{
	input "logInfo", "bool", title: "Show Info Logs?",  required: false, defaultValue: true
	input "logWarn", "bool", title: "Show Warning Logs?", required: false, defaultValue: true
	input "logError", "bool", title: "Show Error Logs?",  required: false, defaultValue: true
	input "logDebug", "bool", title: "Show Debug Logs?", description: "Only leave on when required", required: false, defaultValue: false
	input "logTrace", "bool", title: "Show Detailed Logs?", description: "Only Enabled when asked by the developer", required: false, defaultValue: false
	input "autoConnectWs", "bool", required: false, title: "Auto Connect on Initialize?", defaultValue: true
}

Boolean isSocketActive(){ return (Boolean)state.connectionActive }

void updateCookies(String cookies, doInit=true){
	String msg
	msg= "access token Update by Parent."
	if(cookies && state.access_token != cookies){
		msg += doInit ? "  Re-Initializing Device in 10 Seconds..." : ""
		state.access_token= cookies
		if(doInit) runIn(10, 'initialize')
	}
	logInfo(msg)
}

void removeCookies(isParent=false){
	logInfo("Cookie Authentication Cleared by ${isParent ? "Parent" : "Device"} | Scheduled Refreshes also cancelled!")
	close()
	state.access_token= null
}

def refresh(){
	logInfo("refresh() called")
}

def installed(){
	logInfo("installed() called")
	updated()
}

def triggerInitialize(){
	logInfo("Trigger initialize called")
	unschedule('initialize'); runIn(3, "updated") }

def updated(){
	logInfo("updated() called")
	unschedule()
	state.remove('reconnectDelay') // state.reconnectDelay= 1
	state.remove('access_token')
	if(advLogsActive()){ runIn(1800, "logsOff") }
	initialize()
}

def initialize(){
	logInfo('initialize() called')
	logTrace("Scheduled close for 1 seconds")
	runIn(1,"close")
	if(minVersionFailed()){ logError("CODE UPDATE REQUIRED to RESUME operation. No WebSocket Connections will be made."); return }
	state.remove('warnHistory'); state.remove('errorHistory')

	if(settings.autoConnectWs != false){
		if(!state.access_token) state.access_token= (String)parent?.getCookieS()
		if(state.access_token){
			logTrace("Scheduled auto - connect for 3 seconds")
			runIn(3,"connect")
		} else{
			logInfo("Skipping Socket Open... Cookie Data is Missing $state.access_token")
		}
	} else{
		logInfo("Skipping Socket Open... autoconnect disabled")
	}
}

Boolean advLogsActive(){ return ((Boolean)settings.logDebug || (Boolean)settings.logTrace) }

void logsOff(){
	device.updateSetting("logDebug",[value:"false",type:"bool"])
	device.updateSetting("logTrace",[value:"false",type:"bool"])
	log.debug "Disabling debug logs"
}

static String getWssEndpoint()			{ return "wss://ws.openapi.husqvarna.dev/v1"}

def connect(){
	if(!state.access_token){ logError("connect: no access token"); return }
	try{
		Map headers= [
			//"Connection": "keep-alive",
			//"Pragma": "no-cache",
			//"Cache-Control": "no-cache",
			"Authorization": "Bearer ${state.access_token}"
		]
		logTrace("connect called")
		String url= getWssEndpoint()
		// log.info "Connect URL: $url"
		interfaces.webSocket.connect(url, headers: headers)
	} catch(ex){
		logError("WebSocket connect failed | ${ex}", false, ex)
	}
}

def close(){
	logInfo("close() called")
	state.closepending=true
	updSocketStatus(false)
	interfaces.webSocket.close()
}

def reconnectWebSocket(){
	// first delay is 4 seconds, doubles every time
	Long d
	d= state.reconnectDelay ?: 1L
	d *= 4L
	// don't let the delay get too crazy, max it out at 10 minutes
	if(d > 600L) d= 600L
	state.reconnectDelay= d
	logInfo("reconnectWebSocket() called delay: $d")
	runIn(d, 'initialize')
}
/*
def sendWsMsg(String s){
	interfaces?.webSocket?.sendMessage(s as String)
} */

void updSocketStatus(Boolean active){
	state.connectionActive= active
	parent.webSocketStatus(active)
}

def webSocketStatus(String status){
	logTrace("Websocket Status Event | ${status}")
	if(status.startsWith('failure: ')){
		logWarn("Websocket Failure Message: ${status}")
	} else if(status == 'status: open'){
		logInfo("WS Connection is Open")
		// success! reset reconnect delay
		// pauseExecution(1000)
		unschedule('initialize')
		updSocketStatus(true)
		state.closepending=false
		state.remove('reconnectDelay') // state.reconnectDelay= 1
		return
	} else if(status == "status: closing"){
		logWarn("WebSocket connection closing.")
		if((Boolean)state.closepending){
			state.closepending=false
			return
		}
	} else if(status?.startsWith("send error: ")){
		logError("Websocket Send Error: $status")
	} else logWarn("WebSocket error, reconnecting. $status", false)
	close() // updSocketStatus(false)
	reconnectWebSocket()
}




def parse(message){
	 log.debug("parsed ${message}")

	parent.wsEvtHandler( (Map)new JsonSlurper().parseText(message) )

	//String newMsg= strFromHex(message)
	//logTrace("decodedMsg: ${newMsg}")
/*	if(newMsg){
		parseIncomingMessage(newMsg)
	} */
}



String getCookieVal(){ return state.cookie ?: sNULL }

Integer stateSize(){ String j= new groovy.json.JsonOutput().toJson((Map)state); return j?.length() }
Integer stateSizePerc(){ return (int) ((stateSize() / 100000)*100).toDouble().round(0) }

// public String gitBranch(){
//	 if(gitBranchFLD == sNULL){ gitBranchFLD= (String) parent.gitBranch() }
//	 return (String)gitBranchFLD
// }

//static Integer versionStr2Int(String str){ return str ? str.replaceAll("\\.", sBLANK)?.toInteger() : null }

Boolean minVersionFailed(){
	return false
/*	try{
		Integer minDevVer= (Integer)parent?.minVersions()["wsDevice"] ?: null
		return minDevVer != null && versionStr2Int(devVersion()) < minDevVer
	} catch (ignored){
		return false
	} */
}

static String getDtNow(){
	Date now= new Date()
	return formatDt(now, false)
}

private static TimeZone mTZ(){ return TimeZone.getDefault() } // (TimeZone)location.timeZone

static String formatDt(Date dt, Boolean mdy= true){
	String formatVal= mdy ? "MMM d, yyyy - h:mm:ss a" : "E MMM dd HH:mm:ss z yyyy"
	def tf= new SimpleDateFormat(formatVal)
	if(mTZ()){ tf.setTimeZone(mTZ()) }
	return (String)tf.format(dt)
}

private void addToLogHistory(String logKey, String msg, statusData, Integer max=10){
	Boolean ssOk= true //(stateSizePerc() <= 70)
	String appId= device.getId()

	Map memStore= historyMapFLD[appId] ?: [:]
	List eData
	eData= (List)memStore[logKey] ?: []
	if(eData?.find{ it?.message == msg }){ return }
	if(statusData){ eData.push([dt: getDtNow(), message: msg, status: statusData]) }
	else{ eData.push([dt: getDtNow(), message: msg]) }
	Integer lsiz=eData.size()
	if(!ssOk || lsiz > max){ eData= eData.drop( (lsiz-max) ) }
	updMemStoreItem(logKey, eData)
}

void enableDebugLog(){ device.updateSetting("logDebug",[value:sTRUE,type:"bool"]); logInfo("Debug Logs Enabled From Main App...") }
void disableDebugLog(){ device.updateSetting("logDebug",[value:sFALSE,type:"bool"]); logInfo("Debug Logs Disabled From Main App...") }
void enableTraceLog(){ device.updateSetting("logTrace",[value:sTRUE,type:"bool"]); logInfo("Trace Logs Enabled From Main App...") }
void disableTraceLog(){ device.updateSetting("logTrace",[value:sFALSE,type:"bool"]); logInfo("Trace Logs Disabled From Main App...") }

private void logDebug(String msg){ if((Boolean)settings.logDebug){ log.debug logPrefix(msg, "purple") } }
private void logInfo(String msg){ if((Boolean)settings.logInfo != false){ log.info sSPACE + logPrefix(msg, "#0299b1") } }
private void logTrace(String msg){ if((Boolean)settings.logTrace){ log.trace logPrefix(msg, sCLRGRY) } }
private void logWarn(String msg, Boolean noHist=false){ if((Boolean)settings.logWarn != false){ log.warn sSPACE + logPrefix(msg, sCLRORG) }; if(!noHist){ addToLogHistory("warnHistory", msg, null, 15) } }
static String span(String str, String clr=sNULL, String sz=sNULL, Boolean bld=false, Boolean br=false){ return (String) str ? "<span ${(clr || sz || bld) ? "style='${clr ? "color: ${clr};" : sBLANK}${sz ? "font-size: ${sz};" : sBLANK}${bld ? "font-weight: bold;" : sBLANK}'" : sBLANK}>${str}</span>${br ? sLINEBR : sBLANK}" : sBLANK }

void logError(String msg, Boolean noHist=false, ex=null){
	if((Boolean)settings.logError != false){
		log.error logPrefix(msg, sCLRRED)
		String a
		try{
			if(ex) a= getExceptionMessageWithLine(ex)
		} catch(ignored){
		}
		if(a) log.error logPrefix(a, sCLRRED)
	}
	if(!noHist){ addToLogHistory("errorHistory", msg, null, 15) }
}

static String logPrefix(String msg, String color= sNULL){
	return span("Socket (v" + devVersionFLD + ") | ", sCLRGRY) + span(msg, color)
}

Map getLogHistory(){
	return [ warnings: getMemStoreItem("warnHistory") ?: [], errors: getMemStoreItem("errorHistory") ?: [], speech: getMemStoreItem("speechHistory") ?: [] ]
}

void clearLogHistory(){
	updMemStoreItem("warnHistory", [])
	updMemStoreItem("errorHistory",[])
	mb()
}

static String getObjType(obj){
	if(obj instanceof String){return "String"}
	else if(obj instanceof GString){return "GString"}
	else if(obj instanceof Map){return "Map"}
	else if(obj instanceof LinkedHashMap){return "LinkedHashMap"}
	else if(obj instanceof HashMap){return "HashMap"}
	else if(obj instanceof List){return "List"}
	else if(obj instanceof ArrayList){return "ArrayList"}
	else if(obj instanceof Integer){return "Integer"}
	else if(obj instanceof BigInteger){return "BigInteger"}
	else if(obj instanceof Long){return "Long"}
	else if(obj instanceof Boolean){return "Boolean"}
	else if(obj instanceof BigDecimal){return "BigDecimal"}
	else if(obj instanceof Float){return "Float"}
	else if(obj instanceof Byte){return "Byte"}
	else{ return "unknown"}
}

Map getDeviceMetrics(){
	Map out= [:]
	def cntItems= state?.findAll{ it?.key?.startsWith("use_") }
	def errItems= state?.findAll{ it?.key?.startsWith("err_") }
	if(cntItems?.size()){
		out["usage"]= [:]
		cntItems?.each{ k,v -> out.usage[k?.toString()?.replace("use_", sBLANK) as String]= v as Integer ?: 0 }
	}
	if(errItems?.size()){
		out["errors"]= [:]
		errItems?.each{ k,v -> out.errors[k?.toString()?.replace("err_", sBLANK) as String]= v as Integer ?: 0 }
	}
	return out
}

// FIELD VARIABLE FUNCTIONS
private void updMemStoreItem(String key, val){
	String appId= device.getId()
	Map memStore= historyMapFLD[appId] ?: [:]
	memStore[key]= val
	historyMapFLD[appId]= memStore
	historyMapFLD= historyMapFLD
	// log.debug("updMemStoreItem(${key}): ${memStore[key]}")
}

private List getMemStoreItem(String key){
	String appId= device.getId()
	Map memStore= historyMapFLD[appId] ?: [:]
	return (List)memStore[key] ?: []
}

// Memory Barrier
@Field static java.util.concurrent.Semaphore theMBLockFLD=new java.util.concurrent.Semaphore(0)

static void mb(String meth=sNULL){
	if((Boolean)theMBLockFLD.tryAcquire()){
		theMBLockFLD.release()
	}
}
