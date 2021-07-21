/**
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *	in compliance with the License. You may obtain a copy of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *	for the specific language governing permissions and limitations under the License.
 *
 *  Modified July 21, 2021
 */

static String getVersionNum() 		{ return "00.00.01" }
static String getVersionLabel() 	{ return "Husqvarna AutoMower, version ${getVersionNum()}" }

import groovy.transform.Field

@Field static final String sNULL	= (String)null
@Field static final String sBLANK	= ''
@Field static final String sSPACE	= ' '
@Field static final String sCLRRED	= 'red'
@Field static final String sCLRGRY	= 'gray'
@Field static final String sCLRORG	= 'orange'
@Field static final String sLINEBR	= '<br>'

metadata {
	definition (
		name:			"Husqvarna AutoMower",
		namespace:		"imnotbob",
		author:			"Eric S.",
		//importUrl:   	"https://raw.githubusercontent.com/SANdood/Ecobee-Suite/master/devicetypes/sandood/ecobee-suite-thermostat.src/ecobee-suite-thermostat.groovy"
	)
	{
		capability "Actuator"
		capability "Sensor"
		capability "Refresh"
		capability "Motion Sensor"
		capability "Battery"
		capability "Power Source"

		attribute 'mowerStatus',		'STRING'
			//MAIN_AREA, SECONDARY_AREA, HOME, DEMO, UNKNOWN
		attribute 'mowerActivity',	'STRING'
			//UNKNOWN, NOT_APPLICABLE, MOWING, GOING_HOME, CHARGING, LEAVING, PARKED_IN_CS, STOPPED_IN_GARDEN
		attribute 'mowerState',	'STRING'
			//UNKNOWN, NOT_APPLICABLE, PAUSED, IN_OPERATION, WAIT_UPDATING, WAIT_POWER_UP, RESTRICTED,
			// OFF, STOPPED, ERROR, FATAL_ERROR, ERROR_AT_POWER_UP
		attribute 'mowerConnected',	'STRING' // TRUE or FALSE
		attribute 'mowerTimeStamp',	'STRING' // LAST TIME connected (EPOCH LONG)
		//attribute 'battery'		'NUMBER' // Battery %
		//attribute 'motion'		'ENUM' // active, inactive
		//attribute 'powerSource'	'ENUM' // "battery", "dc", "mains", "unknown"
		attribute 'errorCode',		'STRING' // current error code
		attribute 'errorTimeStamp',	'NUMBER' // (EPOCH LONG)
		attribute 'plannerNextStart',	'NUMBER' // (EPOCH LONG)
		attribute 'plannerOverride',	'STRING' // Override Action
		attribute 'name', 'STRING'
		attribute 'model', 'STRING'
		attribute 'serialNumber', 'STRING'

		attribute 'apiConnected',						'STRING'
		attribute 'debugEventFromParent',				'STRING'		// Read only
		attribute 'debugLevel', 						'NUMBER'		// Read only - changed in preferences
		attribute 'lastPoll', 							'STRING'


		command "start",		 			['NUMBER'] // duration
		command "pause", 					[]
		command "parkuntilnext",			[] // until next schedule
		command "parkindefinite", 			[] // park until further notice
		command "park",						['NUMBER'] // duration in minutes
		command "resumeSchedule", 			[]
	}

	preferences {
		input(name: "dummy", type: "text", title: "${getVersionLabel()}", description: " ", required: false)
	}
}

// parse events into attributes
def parse(String description) {
	LOG("parse() --> Parsing ${description}", 4, sTRACE)
}

def refresh(Boolean force=false) {
	// No longer require forcePoll on every refresh - just get whatever has changed
	LOG("refresh() - calling pollChildren ${force?(forced):sBLANK}, deviceId = ${getDeviceId()}",2,sINFO)
	parent.pollFromChild(getDeviceId(), force) // tell parent to just poll me silently -- can't pass child/this for some reason
}

def doRefresh() {
	// Pressing refresh within 6 seconds of the prior refresh completing will force a complete poll - otherwise changes only
	refresh(state.lastDoRefresh?((now()-state.lastDoRefresh)<6000):false)
	state.lastDoRefresh = now()	// reset the timer after the UI has been updated
}

@SuppressWarnings('unused')
def forceRefresh() {
	refresh(true)
}

def installed() {
	LOG("${device.label} being installed",2,sINFO)
	if (device.label?.contains('TestingForInstall')) return	// we're just going to be deleted in a second...
	updated()
}

def uninstalled() {
	LOG("${device.label} being uninstalled",2,sINFO)
}

def updated() {
	LOG("${getVersionLabel()} updated",1,sTRACE)

	if (device.displayName.contains('TestingForInstall')) { return }

	state.version = getVersionLabel()
	updateDataValue("myVersion", getVersionLabel())
	runIn(2, 'forceRefresh', [overwrite: true])
}

def poll() {
	LOG("Executing 'poll' using parent App", 2, sINFO)
	parent.pollFromChild(getDeviceId(), false) // tell parent to just poll me silently -- can't pass child/this for some reason
}

def generateEvent(Map updates) {
	//log.error "generateEvent(Map): ${updates}"
	generateEvent([updates])
}

def generateEvent(List<Map<String,Object>> updates) {
	//log.debug "updates: ${updates}"
	//if (!state.version || (state.version != getVersionLabel())) updated()
	String myVersion = getDataValue("myVersion")
	if (!myVersion || (myVersion != getVersionLabel())) updated()
	Long startMS = now()
	Boolean debugLevelFour = debugLevel(4)
	if (debugLevelFour) LOG("generateEvent(): parsing data ${updates}",4, sTRACE)
	//LOG("Debug level of parent: ${getParentSetting('debugLevel')}", 4, sDEBUG)
//	String linkText = device.displayName
	Boolean forceChange = false

	Integer objectsUpdated = 0

	if(updates) {
		updates.each { update ->
			update.each { String name, value ->
				String sendValue = value.toString()
				Boolean isChange = isStateChange(device, name, sendValue)
				if(isChange) {
					//def eventFront = [name: name, linkText: linkText, handlerName: name]
					Map eventFront = [name: name ]
					objectsUpdated++
					Map event
					if (debugLevelFour) LOG("generateEvent() - In each loop: object #${objectsUpdated} name: ${name} value: ${value}", 5, sTRACE)
					event = eventFront + [value: sendValue]

					switch (name) {
						case 'forced':
							forceChange = (sendValue == 'true')
							break

						case 'id':
							state.id = sendValue
							event = null
							break

						case 'lastPoll':
							if (debugLevelFour) event = eventFront + [value: sendValue, descriptionText: "Poll: " + sendValue ]
							else event = null
							break

						case 'apiConnected':
							// only display in the devices' log if we are in debug level 4 or 5
							if (forceChange) event = eventFront + [value: sendValue, descriptionText: "API Connection is ${value}" ]
							break

						case 'debugEventFromParent':
						case 'appdebug':
							event = eventFront + [value: sendValue ] //, descriptionText: "-> ${sendValue}" ]
							Integer ix = sendValue.lastIndexOf(" ")
							String msg = sendValue.substring(0, ix)
							String type = sendValue.substring(ix + 1).replaceAll("[()]", "")
							switch (type) {
								case sERROR:
									LOG(msg,1,sERROR)
									break
								case sTRACE:
									LOG(msg, 1, sTRACE)
									break
								case sINFO:
									LOG(msg, 1, sINFO)
									break
								case sWARN:
									LOG(msg, 1, sWARN)
									break
								default:
									LOG(msg, 1, sDEBUG)
							}
							break

						case 'debugLevel':
							String sendText = (sendValue && (sendValue != 'null') && (sendValue != "")) ? sendValue : 'null'
							updateDataValue('debugLevel', sendText)
							event = eventFront + [value: sendText, descriptionText: "debugLevel is ${sendValue}" ]
							break

						default:
							if (name.endsWith("Updated")) {		// internal timestamps for updates
								event = eventFront + [value: sendValue, descriptionText: "${name} at ${sendValue}" ]
							} else {
								event = eventFront + [value: sendValue, descriptionText: "${name} is ${sendValue}" ]
							}
							break
					}
					if (event) {
						if (debugLevelFour) LOG("generateEvent() - Out of switch{}, calling sendevent(${event})", 4, sTRACE)
						sendEvent(event)
					}
				} else LOG("${name} did not change", 5, sTRACE)
			}
		}
	} else LOG('no updates')
	Long elapsed = now() - startMS
	LOG("Updated ${objectsUpdated} object${objectsUpdated!=1?'s':''} (${elapsed}ms)", 4, sINFO)
}

// ***************************************************************************
// commands
// API calls and UI handling
// ***************************************************************************
void start(mins) {
	LOG("start($mins)", 3,  sTRACE)
	Map foo = [data:[type:'Start',attributes:[duration:mins]]]
	if(parent.sendCmdToHusqvarna((String)state.id, foo)) {
		LOG("start($mins)() sent",4, sTRACE)
	}
}

void pause(){
	LOG("pause",3, sTRACE)
	Map foo = [data:[type:'Pause']]
	if(parent.sendCmdToHusqvarna((String)state.id, foo)) {
	   LOG("pause sent",4, sTRACE)
	}
}

@SuppressWarnings('unused')
void parkuntilnext() {
	LOG("parkuntilnext()", 3, sTRACE)
	Map foo = [data:[type:'ParkUntilNextSchedule']]
	if(parent.sendCmdToHusqvarna((String)state.id, foo)) {
		LOG("parkuntilnext() sent",4, sTRACE)
	}
}

void parkindefinite() {
	LOG("parkindefinite()",3,sTRACE)
	log.warn "state.id: ${state.id} getDeviceId(): ${getDeviceId()}"
	Map foo = [data:[type:'ParkUntilFurtherNotice']]
	if(parent.sendCmdToHusqvarna((String)state.id, foo)) {
		LOG("parkindefinite() sent",4, sTRACE)
	}
}

void park(mins) {
	LOG("park($mins)",3,sTRACE)
	Map foo = [data:[type:'Park',attributes:[duration:mins]]]
	if(parent.sendCmdToHusqvarna((String)state.id, foo)) {
		LOG("park($mins)() sent",4, sTRACE)
	}
}

void resumeSchedule() {
	LOG("resumeSchedule",3,sTRACE)
	Map foo = [data:[type:'ResumeSchedule']]
	if(parent.sendCmdToHusqvarna((String)state.id, foo)) {
		LOG("resumeSchedule() sent",4, sTRACE)
	}
}

void off() {
	LOG('off()', 4,  sTRACE)
	parkindefinite()
}

void on() {
	LOG('on()', 4,  sTRACE)
	resumeSchedule()
}

String getDeviceId() {
	def deviceId = ((String)device.deviceNetworkId).split(/\./).last()
	LOG("getDeviceId() returning ${deviceId}", 4)
	return deviceId
}

static String span(String str, String clr=sNULL, String sz=sNULL, Boolean bld=false, Boolean br=false){ return str ? "<span ${(clr || sz || bld) ? "style='${clr ? "color: ${clr};" : sBLANK}${sz ? "font-size: ${sz};" : sBLANK}${bld ? "font-weight: bold;" : sBLANK}'" : sBLANK}>${str}</span>${br ? sLINEBR : sBLANK}" : sBLANK }

Integer getIDebugLevel(){
	return (getDataValue('debugLevel') ?: (device.currentValue('debugLevel') ?: (getParentSetting('debugLevel') ?: 3))) as Integer
}

Boolean debugLevel(Integer level=3){
	return (getIDebugLevel() >= level)
}

void LOG(message, Integer level=3, String logType=sDEBUG, ex=null, Boolean event=false, Boolean displayEvent=false) {
	if(logType == sNULL) logType = sDEBUG
	String prefix = sBLANK

	if(logType == sERROR){
		String a = getTimestamp()
		state.lastLOGerror = "${message} @ "+a
		state.LastLOGerrorDate = a
	} else {
		Integer dbgLevel = getIDebugLevel()
		if (level > dbgLevel) return	// let's not waste CPU cycles if we don't have to...
	}

	if(!lLOGTYPES.contains(logType)){
		logerror("LOG() - Received logType (${logType}) which is not in the list of allowed types ${lLOGTYPES}, message: ${message}, level: ${level}")
		logType = sDEBUG
	}

	if( dbgLevel == 5 ){ prefix = 'LOG: ' }
	"log${logType}"("${prefix}${message}", ex)
	if (event) { debugEvent(message+" (${logType})", displayEvent) }
}

@SuppressWarnings('unused')
private void logdebug(String msg, ex=null){ log.debug logPrefix(msg, "purple") }
@SuppressWarnings('unused')
private void loginfo(String msg, ex=null){ log.info sSPACE + logPrefix(msg, "#0299b1") }
@SuppressWarnings('unused')
private void logtrace(String msg, ex=null){ log.trace logPrefix(msg, sCLRGRY) }
@SuppressWarnings('unused')
private void logwarn(String msg, ex=null){ logexception(msg,ex,sWARN, sCLRORG) }
@SuppressWarnings('unused')
void logerror(String msg, ex=null){ logexception(msg,ex,sERROR, sCLRRED) }

void logexception(String msg, ex=null, String typ, String clr) {
	String msg1 = ex ? " Exception: ${ex}" : sBLANK
	log."$typ" logPrefix(msg+msg1, clr)
	String a
	try {
		if(ex) a = getExceptionMessageWithLine(ex)
	} catch (ignored){ }
	if(a) log."$typ" logPrefix(a, clr)
}

static String logPrefix(String msg, String color = sNULL){
	return span("AutoMower Device (v" + getVersionNum() + ") | ", sCLRGRY) + span(msg, color)
}

void debugEvent(message, Boolean displayEvent = false) {
	def results = [
		name: "appdebug",
		descriptionText: message,
		displayed: displayEvent,
		isStateChange: true
	]
	if ( debugLevel(4) ) { log.debug "Generating AppDebug Event: ${results}" }
	sendEvent (results)
}

def getParentSetting(String settingName) {
	return parent?."${settingName}"
}

static String getPlatform(){ return sHUBPLATFORM }
@Field static final String sHUBPLATFORM		= 'Hubitat'

@Field static final List<String> lLOGTYPES =			['error', 'debug', 'info', 'trace', 'warn']

static String getInfo()				{ return sINFO }
static String getWarn()				{ return sWARN }
static String getTrace()			{ return sTRACE }
static String getDebug()			{ return sDEBUG }
static String getError()			{ return sERROR }

@Field static final String sDEBUG		= 'debug'
@Field static final String sERROR		= 'error'
@Field static final String sINFO		= 'info'
@Field static final String sTRACE		= 'trace'
@Field static final String sWARN		= 'warn'
