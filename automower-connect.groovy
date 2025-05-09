/*
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *	in compliance with the License. You may obtain a copy of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *	for the specific language governing permissions and limitations under the License.
 *
 *	Husqvarna AutoMower
 *
 *  Modified May 3, 2025
 *
 *  Instructions:
 *	Go to developer.husqvarnagroup.cloud
 *	- Sign in with your AutoConnect credentials
 *	- +Create application
 *		- Name it, URI for redirect https://cloud.hubitat.com/oauth/stateredirect
 *		- Connect this to authentication api and automower api
 *		- after saving, note application key and application secret to enter into settings here
 *
 *  Note: currently Husqvarna allows a total of 10K requests a month.  This app must poll so this must be taken into account
 *	This works out to shortest poll is every 5 minutes, with little remaining headroom
 */

//file:noinspection GroovyDoubleNegation
//file:noinspection GroovyUnusedAssignment
//file:noinspection GroovySillyAssignment
//file:noinspection unused
//file:noinspection GroovyVariableNotAssigned
//file:noinspection GrDeprecatedAPIUsage

import groovy.json.*
import groovy.transform.Field
import java.text.SimpleDateFormat

static String getVersionNum()		{ return "00.00.08" }
static String getVersionLabel()		{ return "Husqvarna Automower Manager, version "+getVersionNum() }
static String getMyNamespace()		{ return "imnotbob" }

@Field static final Integer iWATCHDOGINTERVAL=10	// In minutes
@Field static final Integer iREATTEMPTINTERVAL=30	// In seconds

Integer gtPollingInterval(){
	Integer interval= ((settings?.pollingInterval!= null) ? settings.pollingInterval : 15) as Integer // in minutes
	Integer mult= getWWebSocketStatus() ? 8 : 1
	return (interval * mult).toInteger()
}

Integer getMinMinsBtwPolls() {
	Integer mult= getWWebSocketStatus() ? 20 : 1
	return (3 * mult).toInteger()
}

static String getAutoMowerName()	{ return "Husqvarna AutoMower" }

@Field static final String sNULL	= (String)null
@Field static final String sBLANK	= ''
@Field static final String sSPACE	= ' '
@Field static final String sCLRRED	= 'red'
@Field static final String sCLRGRY	= 'gray'
@Field static final String sCLRORG	= 'orange'
@Field static final String sLINEBR	= '<br>'
@Field static final String sLOST	= 'lost'
@Field static final String sFULL	= 'full'
@Field static final String sBOOL	= 'bool'
@Field static final String sENUM	= 'enum'
@Field static final String sPOLL	= 'poll'

definition(
	name:			"Husqvarna AutoMower Manager",
	namespace:		myNamespace,
	author:			"imnot_bob",
	description:	"Connect your Husqvarna AutoMowers, along with a Suite of Helper Apps.",
	category:		"Integrations",
	iconUrl:		"https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
	iconX2Url:		"https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
	iconX3Url:		"https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
	importUrl:		"https://raw.githubusercontent.com/imnotbob/AutoMower/master/automower-connect.groovy",
	singleInstance:	true,
	oauth:			true
)

preferences {
	page(name: "mainPage")
	page(name: "removePage")
	page(name: "authPage")
	page(name: "mowersPage")
	page(name: "preferencesPage")

	page(name: "debugDashboardPage")
	page(name: "refreshAuthTokenPage")
}

mappings {
	path("/oauth/initialize"){action: [GET: "oauthInitUrl"]}
	path("/callback"){action: [GET: "callback"]}
	path("/oauth/callback"){action: [GET: "callback"]}
}

void installed(){
	LOG("Installed with settings: ${settings}",1,sTRACE)
	initialize()
}

void uninstalled(){
	LOG("Uninstalling...",0,sWARN)
	unschedule()
	unsubscribe()
	removeChildDevices( (List)getAllChildDevices(), false )	// delete all my children!
	// Child apps are supposedly automatically deleted.
}

void updated(){
	LOG("Updated with settings: ${settings}",2,sTRACE)
	cleanupStates()
	initialize()
}

@Field static Random randomSeed=new Random()

void initialize(){
	unsubscribe()
	unschedule()

	createSocketDev()

	LOG("${getVersionLabel()} Initializing...", 2, sDEBUG)

	if(settings.pollingInterval == null){ app.updateSetting('pollingInterval', "15") }

	Integer tt=randomSeed.nextInt(100)	// get the random number generator going

	state.inPollChildren=true
	state.skipTime=wnow()

	if((String)state.authToken && (Boolean)state.initialized) {
		Long timeBeforeExpiry= state.authTokenExpires ? (Long)state.authTokenExpires - wnow() : 0L
		if(timeBeforeExpiry > 0) {
			//state.connected=sFULL
			apiRestored(false)
		}else apiLost("initialize found expired token")
	}else state.connected=sWARN

	updateMyLabel()
	state.reAttempt=0
	state.remove('reAttempt')
	if(state.inTimeoutRetry){ state.inTimeoutRetry=0; state.remove('inTimeoutRetry') }

	Map updatesLog=[mowerUpdated:true, runtimeUpdated:true, forcePoll:true, getWeather:true, alertsUpdated:true, extendRTUpdated:true ]
	state.updatesLog=updatesLog

	state.numAvailMowers=0
	state.mowerData=[:]

	if(!(Boolean)state.initialized){
		state.initialized=true
		// These two below are for debugging and statistics purposes
		state.initializedEpoch=wnow()
		state.initializedDate=getDtNow() // getTimeStamp
	}

	Map myMowers
	myMowers=null
	clearLastPolls()
	myMowers=getAutoMowers(true, "initialize")
	Boolean apiOk=(myMowers!=null)

	// Create children, This should only be needed during initial setup and when mowers or sensors are added or removed.
	Boolean aOK
	aOK=apiOk
	aOK=(aOK && ((List<String>)settings.mowers)?.size() > 0)
	if(aOK) aOK=createChildrenMowers()
	if(aOK) deleteUnusedChildren()

	if(aOK && myMowers)
		Boolean a=updateMowerChildren()

	subscribe(location, "systemStart", rebooted)					// re-initialize if the hub reboots

	state.inPollChildren=false
	state.remove('inPollChildren')
	state.remove('skipTime')
	state.lastScheduledPoll=null
	state.lastScheduledPollDate=sNULL

	if(aOK){ forceNextPoll() }

	// Schedule the various handlers
	checkPolls('initialize() ', apiOk, true)

	//send activity feeds to tell devices connection status
	String notificationMessage=aOK ? "is connected" : (apiOk ? "had an error during setup of devices" : "api not connected")

	LOG("${getVersionLabel()} - initialization complete "+notificationMessage,2,sDEBUG)
	if(!state.versionLabel) state.versionLabel=getVersionLabel()
	chkRestartSocket(true)
	if(aOK) runIn(8, sPOLL, [overwrite: true])
}

void rebooted(evt){
	LOG("Hub rebooted, re-initializing", 2, sTRACE)
	initialize()
}






def mainPage(){
	String version=getVersionLabel()
	Boolean deviceHandlersInstalled
	Boolean readyToInstall //=false

	deviceHandlersInstalled=testForDeviceHandlers()
	readyToInstall=deviceHandlersInstalled

	dynamicPage(name: "mainPage", title: pageTitle(version.replace('er, v',"er\nV")), install: readyToInstall, uninstall: false, submitOnChange: true){

		// If no device Handlers we cannot proceed
		if(!(Boolean)state.initialized && !deviceHandlersInstalled){
			section(){
				paragraph "ERROR!\n\nYou MUST add the ${getAutoMowerName()} Device Handlers to the IDE BEFORE running setup."
			}
		}else{
			readyToInstall=true
		}

		if((Boolean)state.initialized && !(String)state.authToken){
			section(){
				paragraph(getFormat("warning", "You are no longer connected to the Husqvarna API. Please re-Authorize below."))
			}
			def dev= getSocketDevice()
			if(dev) dev.removeCookies(true)
		}

		if((String)state.authToken && !(Boolean)state.initialized){
			section(){
				paragraph "Please 'click \'Done\'' to save your credentials. Then re-open the AutoMower Manager to continue the setup."
			}
		}

		if((String)state.authToken && (Boolean)state.initialized){
			if(((List<String>)settings.mowers)?.size() > 0){
/*				section(sectionTitle("Helpers")){
					href ("helperAppsPage", title: inputTitle("Helper Applications"), description: "'Click' to manage Helper 'Applications'")
				}*/
			}
			section(sectionTitle("AutoMower Devices")){
				Integer howManyMowersSel=((List<String>)settings.mowers)?.size() ?: 0
				Integer howManyMowers=state.numAvailMowers ?: 0

				// Mowers
				href ("mowersPage", title: inputTitle("Mowers"), description: "'Click' to select AutoMowers [${howManyMowersSel}/${howManyMowers}]")
			}
		}

		section(sectionTitle("Preferences")){
			href ("preferencesPage", title: inputTitle("AutoMower Preferences"), description: "'Click' to manage global Preferences")
		}

		String authDesc=((String)state.authToken) ? "[Connected]\n" :"[Not Connected]\n"
		section(sectionTitle("Authentication")){
			href ("authPage", title: inputTitle("AutoMower API Authorization"), description: "${authDesc}'Click' for AutoMower Authentication")
		}
		if( debugLevel(5) ){
			section (sectionTitle("Debug Dashboard")){
				href ("debugDashboardPage", description: "${HE?'Click':'Tap'} to enter the Debug Dashboard", title: inputTitle("Debug Dashboard"))
			}
		}
		section(sectionTitle( "Removal")){
			href ("removePage", description: "'Click' to remove ${cleanAppName((String)app.label?:(String)app.name)}", title: inputTitle("Remove AutoMower Manager"))
		}

		section (sectionTitle("Naming")){
			String defaultName="AutoMower Manager"
			String defaultLabel
			if(!(String)state.appDisplayName){
				defaultLabel=defaultName
				app.updateLabel(defaultName)
				state.appDisplayName=defaultName
			}else{
				defaultLabel=(String)state.appDisplayName
			}
			label(name: "name", title: inputTitle("Assign a name"), required: false, defaultValue: defaultLabel, submitOnChange: true, width: 6)
			if(!app.label){
				app.updateLabel(defaultLabel)
				state.appDisplayName=defaultLabel
			}else{
				state.appDisplayName=(String)app.label
			}

			if(((String)app.label).contains('<span')){
				if((String)state.appDisplayName && !((String)state.appDisplayName).contains('<span ')){
					app.updateLabel((String)state.appDisplayName)
				}else{
					String myLabel=((String)app.label).substring(0, ((String)app.label).indexOf('<span'))
					state.appDisplayName=myLabel
					app.updateLabel((String)state.appDisplayName)
				}
			}
		}

		section(){
			paragraph(getFormat("line")+"<div style='color:#5BBD76;text-align:center'>${getVersionLabel()}<br>")
		}
	}
}

def removePage(){
	dynamicPage(name: "removePage", title: pageTitle("AutoMower Manager\nRemove AutoMower Manager and its Children"), install: false, uninstall: true){
		section (){
			paragraph(getFormat("warning", "Removing AutoMower Manager also removes all Mower automations and Devices!"))
		}
	}
}

Boolean initializeEndpoint(Boolean disableRetry=false) {
	String accessToken
	accessToken=(String)state.accessToken
	if(!accessToken){
		try {
			accessToken=createAccessToken()
		} catch(Exception e){
			LOG("authPage() --> No OAuth Access token", 3, sERROR, e)
		}
		if(!accessToken && !disableRetry){
			enableOauth()
			return initializeEndpoint(true)
		}
	}
	return (!!(String)state.accessToken)
}

// try to enable oauth on HE for this app
private void enableOauth(){
	Map params=[
		uri: "http://localhost:8080/app/edit/update?_action_update=Update&oauthEnabled=true&id=${app.appTypeId}".toString(),
		headers: ['Content-Type':'text/html;charset=utf-8']
	]
	try{
		httpPost(params){ resp ->
			//LogTrace("response data: ${resp.data}")
		}
	} catch (e){
		LOG("enableOauth something went wrong: ", 1, sERROR, e)
	}
}

// Setup OAuth between HE and Husqvarna clouds
def authPage(){
	LOG("authPage() --> Begin", 4, sTRACE)

	//log.debug "accessToken: ${state.accessToken}, ${state.accessToken}"

	Boolean success=initializeEndpoint()
	if(!success) {
		if(!state.accessToken){
			LOG("authPage() --> OAuth", 1, sERROR, e)
			LOG("authPage() --> Probable Cause: OAuth not enabled in Hubitat IDE for the 'AutoMower Manager' 'App'", 1, sWARN)
			LOG("authPage() --> No OAuth Access token", 3, sERROR)
			return dynamicPage(name: "authPage", title: pageTitle("AutoMower Manager\nOAuth Initialization Failure"), nextPage: sBLANK, uninstall: true){
				section(){
					paragraph "Error initializing AutoMower Authentication: could not get the OAuth access token.\n\nPlease verify that OAuth has been enabled in " +
							"the Hubitat IDE for the 'AutoMower Manager' 'App', and then try again.\n\nIf this error persists, view Live Logging in the IDE for " +
							"additional error information."
				}
			}
		}
	}

	String description
	description=sBLANK
	Boolean uninstallAllowed, oauthTokenProvided
	uninstallAllowed=false
	oauthTokenProvided=false

	if((String)state.authToken){
		description="You are connected. Click Next/Done below."
		uninstallAllowed=true
		oauthTokenProvided=true
		apiRestored()
	}else{
		description="'Click' to enter AutoMower Credentials"
	}
	// HE OAuth process
	String redirectUrl=oauthInitUrl()

	// get rid of next button until the user is actually auth'd
	if(!oauthTokenProvided){
		LOG("authPage() --> Valid 'HE' OAuth Access token (${state.accessToken}), need AutoMower OAuth token", 3, sTRACE)
		LOG("authPage() --> RedirectUrl=${redirectUrl}", 5, sINFO)
		return dynamicPage(name: "authPage", title: pageTitle("AutoMower Manager\nHusqvarna API Authentication"), nextPage: sBLANK, uninstall: uninstallAllowed){
			oauthSection()
			if(getHusqvarnaApiKey() && getHusqvarnaApiSecret()) {
				section(sectionTitle(" ")){
					paragraph "'Click' below to log in to the Husqvarna service and authorize AutoMower Manager for Hubitat access. Be sure to 'Click' the 'Allow' button on the 2nd page."
					href url: redirectUrl, style: "external", required: true, title: inputTitle("AutoConnect Account Authorization"), description: description
				}
			}
		}
	}else{
		LOG("authPage() --> Valid OAuth token (${(String)state.authToken})", 3, sTRACE)
		return dynamicPage(name: "authPage", title: pageTitle("AutoMower Manager\nHusqvarna API Authentication"), nextPage: "mainPage", uninstall: uninstallAllowed){
			oauthSection()
			if(getHusqvarnaApiKey() && getHusqvarnaApiSecret()) {
				section(sectionTitle(" ")){
					paragraph "Return to the main menu"
					href url:redirectUrl, style: "embedded", state: "complete", title: inputTitle("AutoConnect Account Authorization"), description: description
				}
			}
		}
	}
}

def oauthSection(){
	section(sectionTitle("Husqvarna Oauth credentials")){
		paragraph "Enter Oauth you created from Husqvarna Development portal"
		input(name: "apiKey", title:inputTitle("Enter Oauth Key"), type: "text", required:true, description: "Tap to choose", submitOnChange: true, width: 6)
		input(name: "apiSecret", title:inputTitle("Enter Oauth Secret"), type: "text", required:true, description: "Tap to choose", submitOnChange: true, width: 6)
		String msg= """
  Instructions:
	Go to developer.husqvarnagroup.cloud
	- Sign in with your AutoConnect credentials
	- +Create application
		- Name it, redirect URI should be set https://cloud.hubitat.com/oauth/stateredirect
		- Connect this to authentication api and automower api
		- after saving, note application key and application secret to enter into settings here """
		paragraph msg
	}
}

def mowersPage(params){
	LOG("=====> mowersPage() entered", 5)
	Map mowers=getAutoMowers(true, "mowersPage")

	LOG("mowersPage() -> mower list: ${mowers}",5)
	LOG("mowersPage() starting settings: ${settings}",5)
	LOG("mowersPage() params passed: ${params}", 5, sTRACE)

	dynamicPage(name: "mowersPage", title: pageTitle("AutoMower Manager\nMowers"), params: params, nextPage: sBLANK, content: "mowersPage", uninstall: false){
		section(title: sectionTitle("Mower Selection")){
			if(mowers) {
				paragraph("'Click' below to see the list of AutoMowers available in your AutoConnect account and select the ones you want to connect.")
				LOG("mowersPage(): state.settingsCurrentMowers=${state.settingsCurrentMowers}	mowers=${(List<String>)settings.mowers}", 4, sTRACE)
				if((List<String>)state.settingsCurrentMowers != (List<String>)settings.mowers){
					LOG("state.settingsCurrentMowers != mowers: changes detected!", 4, sTRACE)
					state.settingsCurrentMowers=(List<String>)settings.mowers ?: []
					checkPolls('mowersPage ', true, false)
				}else{
					LOG("state.settingsCurrentMowers == mowers: No changes detected!", 4, sTRACE)
				}
				input(name: "mowers", title:inputTitle("Select Mowers"), type: sENUM, required:false, multiple:true, description: "Tap to choose", params: params,
						options: mowers, submitOnChange: true, width: 6)
			}else paragraph("No mowers found to connect.")
		}
	}
}

def preferencesPage(){
	LOG("=====> preferencesPage() entered. settings: ${settings}", 5)

	dynamicPage(name: "preferencesPage", title: pageTitle("AutoMower Manager\nPreferences"), nextPage: sBLANK){
		List echo=[]
		section(title: sectionTitle("Notifications")){
			paragraph("Notifications are only sent when the AutoConnect API connection is lost and unrecoverable, at most 1 per hour.", width: 8)
		}
		section(title: smallerTitle("Notification Devices")){
			input(name: "notifiers", type: "capability.notification", multiple: true, title: inputTitle("Select Notification Devices"), submitOnChange: true, width: 6,
					required: false /*(!settings.speak || ((settings.musicDevices == null) && (settings.speechDevices == null)))*/)
			if(settings.notifiers){
				echo=settings.notifiers.findAll { (it.deviceNetworkId.contains('|echoSpeaks|') && it.hasCommand('sendAnnouncementToDevices')) }
				if(echo){
					input(name: "echoAnnouncements", type: sBOOL, title: "Use ${echo.size()>1?'simultaneous ':''}Announcements for the Echo Speaks device${echo.size()>1?'s':''}?",
							defaultValue: false, submitOnChange: true)
				}
			}
		}
		section(hideWhenEmpty: (!settings.speechDevices && !settings.musicDevices), title: smallerTitle("Speech Devices")){
			input(name: "speak", type: sBOOL, title: inputTitle("Speak messages?"), required: !settings?.notifiers, defaultValue: false, submitOnChange: true, width: 6)
			if((Boolean)settings.speak){
				input(name: "speechDevices", type: "capability.speechSynthesis", required: (settings.musicDevices == null), title: inputTitle("Select speech devices"),
						multiple: true, submitOnChange: true, hideWhenEmpty: true, width: 4)
				input(name: "musicDevices", type: "capability.musicPlayer", required: (settings.speechDevices == null), title: inputTitle("Select music devices"),
						multiple: true, submitOnChange: true, hideWhenEmpty: true, width: 4)
				input(name: "volume", type: "number", range: "0..100", title: inputTitle("At this volume (%)"), defaultValue: 50, required: false, width: 4)
			}
		}
		if(echo || settings.speak){
			section(smallerTitle("Do Not Disturb")){
				input(name: "speakModes", type: "mode", title: inputTitle('Only speak notifications during these Location Modes:'), required: false, multiple: true, submitOnChange: true, width: 6)
				input(name: "speakTimeStart", type: "time", title: inputTitle('Only speak notifications<br>between...'), required: (settings.speakTimeEnd != null), submitOnChange: true, width: 3)
				input(name: "speakTimeEnd", type: "time", title: inputTitle("<br>...and"), required: (settings.speakTimeStart != null), submitOnChange: true, width: 3)
				String nowOK=((List)settings.speakModes || ((settings.speakTimeStart != null) && (settings.speakTimeEnd != null))) ?
						(" - with the current settings, notifications WOULD ${notifyNowOK()?sBLANK:'NOT '}be spoken now") : sBLANK
				paragraph(getFormat('note', "If both Modes and Times are set, both must be true" + nowOK))
			}
		}
		section(title: sectionTitle("Configuration")){}
		section(title: smallerTitle("Polling Interval")){
			paragraph("How frequently do you want to poll the Husqvarna cloud for changes? (Default 15 mins)", width: 8)
			paragraph(sBLANK, width: 4)
			input(name: "pollingInterval", title:inputTitle("Select Polling Interval")+" (minutes)", type: sENUM, required:false, multiple:false, defaultValue:"15", description: "in Minutes", width: 4,
					options:["6", "10", "15", "30", "60"])
			if(settings.pollingInterval == null){ app.updateSetting('pollingInterval', "15") }
		}
		section(title: sectionTitle("Operations")){}
		section(title: smallerTitle("Debug Log Level")){
			paragraph("Select the debug logging level. Higher levels send more information to IDE Live Logging. A setting of 2 is recommended for normal operations.", width: 8)
			paragraph(sBLANK, width: 4)
			input(name: "debugLevel", title:inputTitle("Select Debug Log Level"), type: sENUM, required:false, multiple:false, defaultValue:"2", description: "2",
					options:["5", "4", "3", "2", "1", "0"], width: 4)
			if(settings.debugLevel == null){ app.updateSetting('debugLevel', "2") }
			generateEventLocalParams() // push down to devices
		}
	}
}

def debugDashboardPage(){
	LOG("=====> debugDashboardPage() entered.", 5)

	dynamicPage(name: "debugDashboardPage", title: sBLANK){
		section(getVersionLabel()){}
		section(sectionTitle("Commands")){
			href(name: "refreshAuthTokenPage", title: sBLANK, required: false, page: "refreshAuthTokenPage", description: "Tap to execute: refreshAuthToken()")
		}

		section(sectionTitle("Settings Information")){
			paragraph "debugLevel: ${getIDebugLevel()}"
			Integer interval= ((settings?.pollingInterval!= null) ? settings.pollingInterval : 15) as Integer // in minutes
			paragraph "pollingInterval (Minutes): ${interval} actual: ${gtPollingInterval()}"
			paragraph "webSocket Operating: ${getWWebSocketStatus()}"
			paragraph "Selected Mowers: ${settings.mowers}"
		}
		section(sectionTitle("Dump of Debug Variables")){
			Map debugParamList=getDebugDump()
			LOG("debugParamList: ${debugParamList}", 4, sDEBUG)
			//if( debugParamList?.size() > 0 ){
			if( debugParamList != null ){
				debugParamList.each { key, value ->
					LOG("Adding paragraph: key:${key} value:${value}", 5, sTRACE)
					paragraph "${key}: ${value}"
				}
			}
		}
		section(sectionTitle("Commands")){
			href ("removePage", description: "Tap to remove AutoMower Manager ", title: sBLANK)
		}
	}
}


def refreshAuthTokenPage(){
	LOG("=====> refreshAuthTokenPage() entered.", 5)
	Boolean a=refreshAuthToken('refreshAuthTokenPage')

	dynamicPage(name: "refreshAuthTokenPage", title: sBLANK){
		section(){
			paragraph "refreshAuthTokenPage() was called"
		}
	}
}

Boolean testForDeviceHandlers(){
	// Only create the dummy devices if we aren't initialized yet
	if((Boolean)state.runTestOnce != null){
		if((Boolean)state.runTestOnce == false){
			List myChildren=(List)getAllChildDevices()
			if(myChildren) removeChildDevices( myChildren, true )	// Delete any leftover dummy (test) children
			state.runTestOnce=null
			return false
		}else{
			return true
		}
	}

	String DNIAdder=wnow().toString()
	String d1Str="dummyMowerDNI-${DNIAdder}"
	def d1
	Boolean success
	success=false
	List myChildren=(List)getAllChildDevices()
	if(myChildren.size() > 0) removeChildDevices( myChildren, true )	// Delete my test children
	LOG("testing for device handlers", 4, sTRACE)
	try {
		d1=addChildDevice(myNamespace, getAutoMowerName(), d1Str, ((List)location.hubs)[0]?.id, ["label":"AutoMower:TestingForInstall", completedSetup:true])
		if((d1 != null) ) success=true
	} catch(Exception e){
		LOG("testForDeviceHandlers", 1, sERROR, e)
		if("${e}".startsWith("com.hubitat.app.exception.UnknownDeviceTypeException")){
			LOG("You MUST add the ${getAutoMowerName()} Device Handlers to the IDE BEFORE running the setup.", 1, sERROR)
		}
	}
	LOG("device handlers=${success}", 4, sINFO)
	Boolean deletedChildren
	deletedChildren=true
	try {
		if(d1) deleteChildDevice(d1Str)
	} catch(Exception e){
		LOG("Error deleting test devices (${d1})",1,sWARN, e)
		deletedChildren=false
	}

	if(!deletedChildren) runIn(5, delayedRemoveChildren, [overwrite: true])
	state.runTestOnce=success
	return success
}

void delayedRemoveChildren(){
	List myChildren=(List)getAllChildDevices()
	if(myChildren.size() > 0) removeChildDevices( myChildren, true )
}

void removeChildDevices(List devices, Boolean dummyOnly=false){
	if(!devices){
		return
	}
	Boolean first
	first=true
	String devName
	devName=sNULL
	try {
		devices?.each {
			devName=it.displayName
			String devDNI=it.deviceNetworkId
			if(!dummyOnly || devDNI?.startsWith('dummy')){
				if(first) {
					first=false
					LOG("Removing ${dummyOnly?'test':'unused'} child devices",3,sTRACE)
				}
				LOG("Removing unused child: ${devDNI} - ${devName}",1,sWARN)
				deleteChildDevice(devDNI)
			}else{
				LOG("Keeping child: ${devDNI} - ${devName}",4,sTRACE)
			}
		}
	} catch(Exception e){
		LOG("Error removing device ${devName}",1,sWARN, e)
	}
}




String getSocketDNI(){
	String myId=app.getId()
	String nmS
	nmS= 'automower_websocket'
	nmS= myId + '|' + nmS
	return nmS
}

def getSocketDevice(){
	return getChildDevice(getSocketDNI())
}

void createSocketDev(){
	String nmS= getSocketDNI()
	def wsDevice= getChildDevice(nmS)
	if(!wsDevice) {
		String wsChildHandlerName= "Husqvarna AutoMower WS"
		def a= addChildDevice("imnotbob", wsChildHandlerName, nmS, null, [name: wsChildHandlerName, label: "Husqvarna AutoMower - WebSocket", completedSetup: true])
	}
}

void chkRestartSocket(Boolean frc=true){
	def dev= getSocketDevice()
	if(dev && (frc || !(Boolean)dev?.isSocketActive())) {
		LOG("chkRestartSocket: re-initializing websocket force: $frc", 1, sTRACE)
		dev.triggerInitialize()
	}
}

Boolean getWWebSocketStatus(){
	return (Boolean)state.websocketActive
}

void webSocketStatus(Boolean active) {
	state.websocketActive= active
}

@SuppressWarnings('GroovyFallthrough')
void wsEvtHandler(Map evt){

	LOG("wsEvtHandler evt: ${evt}", 4, sDEBUG)

//	state.numAvailMowers=((List<Map>)ndata)?.size() ?: 0
	Boolean fndMower,didChg
	fndMower=true
	didChg=false

	String dni=getMowerDNI((String)evt.id)
	if(!(dni in (List<String>)settings.mowers)){
		if(dni) LOG("wsEvtHandler DID NOT FIND $dni in settings", 4, sDEBUG)
		fndMower=false
	}

	Map mowersLocation,mowerLoc
	mowersLocation= state.mowersLocation ?: [:]
	mowerLoc= mowersLocation[dni] ? mowersLocation: [:]

	Map<String, Map> mdata=(Map<String,Map>)state.mowerData ?: [:]

	Map mower= mdata && dni ? mdata[dni] : null
	String typ=evt.type

	if(mower && typ){
		switch(typ){
			case 'battery-event-v2':
			case 'calendar-event-v2':
			case 'cuttingHeight-event-v2':
			case 'headlights-event-v2':
			case 'messages-event-v2':
			case 'mower-event-v2':
			case 'planner-event-v2':
			case 'position-event-v2':

			case 'status-event':

			case 'positions-event':

			case 'settings-event':
				if(evt.attributes){
					Map ma= (Map)mdata[dni].attributes
					((Map)evt.attributes).each {
						LOG("wsEvtHandler - type: ${typ} key: ${it.key} value: ${it.value}", 4, sDEBUG)
						/*
						if((String)it.key in ['cuttingHeight','headlight']){
							Map mas= (Map)ma.settings
							mas[it.key]=it.value
							didChg=true
						}else{
						 */
							if((String)it.key in ['calendar','position','battery','mower','metadata','planner','statistics','message','position', 'cuttingHeight', 'headlight']){
								ma[it.key]=it.value
								didChg=true
							}else{
								LOG("wsEvtHandler NOT FOUND - type: ${typ} key: ${it.key} value: ${it.value}", 4, sDEBUG)
							}
						//}
					}
					//LOG("wsEvtHandler mdata[${dni}]: ${mdata[dni]}", 4, sDEBUG)
					//LOG("wsEvtHandler ma: ${ma}", 4, sDEBUG)
					mdata[dni].attributes=ma
				}
				break
			default:
				LOG("wsEvtHandler NO MATCH - type: ${typ} evt: ${evt}", 4, sDEBUG)

		}
		if(fndMower && didChg && mowerLoc){
			mowerLoc[dni]= getMowerLocation(mower)
			state.mowersLocation=  mowerLoc
		}
	}else{
		if(dni) LOG("wsEvtHandler NO mower or type - type: ${typ} mower: ${mower}", 4, sDEBUG)

	}

	state.mowerData=mdata
	//log.debug "resp: ${state.mowerData}"

	if(fndMower && didChg){
		updTsVal("getAutoUpdDt")
		updateLastPoll(true)

		Boolean a= updateMowerChildren()
	}
}

String getCookieS(){
	return (String)state.authToken
}





static String getCallbackUrl()			{ return "https://cloud.hubitat.com/oauth/stateredirect" }
static String getMowerApiEndpoint()		{ return "https://api.amc.husqvarna.dev/v1" }
static String getApiEndpoint()			{ return "https://api.authentication.husqvarnagroup.dev/v1/oauth2" }
//static String getWssEndpoint()			{ return "wss://ws.openapi.husqvarna.dev/v1"}

//String getBuildRedirectUrl()	{ return "${serverUrl}/oauth/stateredirect?access_token=${state.accessToken}" }
String getStateUrl()			{ return "${getHubUID()}/apps/${app?.id}/callback?access_token=${state?.accessToken}" }


String getHusqvarnaApiKey(){ return (String)settings.apiKey }
String getHusqvarnaApiSecret(){ return (String)settings.apiSecret }

// OAuth Init URL
String oauthInitUrl(){
	LOG("oauthInitUrl", 4)
	state.oauthInitState=getStateUrl() // HE does redirect a little differently
	//log.debug "oauthInitState: ${state.oauthInitState}"

	Map oauthParams=[
		response_type:	"code",
		client_id:	getHusqvarnaApiKey(),					// actually, the AutoMower Manager app's client ID
		scope:		"iam:read amc:api", // was app
		redirect_uri:	getCallbackUrl(),
		state:		state.oauthInitState
	]

	String res= getApiEndpoint()+"/authorize?${toQueryString(oauthParams)}"
	LOG("oauthInitUrl - location: ${res}", 4, sDEBUG)
	return res
}

void parseAuthResponse(resp){
	String msgH="Display http response | "
	//log.debug "response data: ${myObj(resp.data)} ${resp.data}"
	String str
	str=sBLANK
	resp.data.each{
		str += "\n${it.key} --> ${it.value}, "
	}
	LOG(msgH+"response data: ${str}",4,sDEBUG)
	LOG(msgH+"response data object type: ${myObj(resp.data)}",4,sDEBUG)

	str=sBLANK
	resp.getHeaders().each{
		str += "\n${it.name}: ${it.value}, "
	}
	log.debug msgH+"response headers: ${str}"
	log.debug msgH+"isSuccess: ${resp.isSuccess()} | statucode: ${resp.status}"

	//str=sBLANK
	//log.debug "resp param ${resp.params}"
	//resp.params.each{ str += "${it.name}: ${it.value}"}
	//log.debug "response params: ${str}"
}

private static String encodeURIComponent(value){
	// URLEncoder converts spaces to + which is then indistinguishable from any
	// actual + characters in the value. Match encodeURIComponent in ECMAScript
	// which encodes "a+b c" as "a+b%20c" rather than URLEncoder's "a+b+c"
	return URLEncoder.encode(
			"${value}".toString().replaceAll('\\+','__wc_plus__'),
			'UTF-8'
	).replaceAll('\\+','%20').replaceAll('__wc_plus__','+')
}

def callback(){
	LOG("callback()>> params: ${params}" /* params.code ${params.code}, params.state ${params.state}, state.oauthInitState ${state.oauthInitState}"*/, 4, sDEBUG)
	def code=params.code
	String oauthState=params.state
	String eMsg
	eMsg= sNULL

	if(oauthState == state.oauthInitState){
		LOG("callback() --> States matched!", 4)
		Map rdata=[
				grant_type: "authorization_code",
				code	: code,
				client_id : getHusqvarnaApiKey(),
				client_secret : getHusqvarnaApiSecret(),
				state	: oauthState,
				redirect_uri: callbackUrl,
		]

		//String tokenUrl=getApiEndpoint()+"/token?${toQueryString(tokenParams)}"
		String tokenUrl=getApiEndpoint()+"/token"
		String data=rdata.collect{ String k,v -> encodeURIComponent(k)+'='+encodeURIComponent(v) }.join('&')
		Map reqP=[
				uri: tokenUrl,
				query: null,
				contentType: "application/x-www-form-urlencoded",
//				requestContentType: "application/json",
				body: data,
				timeout: 30
		]
		LOG("callback()-->reqP ${reqP}", 4)
		try{
			httpPost(reqP){ resp ->
				if(resp && resp.data && resp.isSuccess()){
//					parseAuthResponse(resp)
					String kk
					resp.data.each{ kk=it.key }
					Map ndata=(Map)new JsonSlurper().parseText(kk)
					log.debug "ndata : ${ndata}"

					state.refreshToken=ndata.refresh_token
					state.authToken=ndata.access_token
					Long tt= wnow() + (ndata.expires_in * 1000)
					//log.error "tt is ${tt}"
					state.authTokenExpires=tt
					atomicState.refreshToken=ndata.refresh_token
					atomicState.authToken=ndata.access_token
					//atomicState.authTokenExpires=tt
					//log.error "state.authTokenExpires is ${state.authTokenExpires}"

					LOG("Expires in ${ndata.expires_in} seconds", 3)
					LOG("swapped token: $ndata; state.refreshToken: ${state.refreshToken}; state.authToken: ${(String)state.authToken}", 3)
					state.remove('oauthInitState')
					eMsg= success()

					def dev= getSocketDevice()
					if(dev){
						dev.updateCookies(ndata.access_token)
						if(!(Boolean)dev.isSocketActive()){ dev.triggerInitialize() }
					}

				}else{ eMsg= fail() }
			}
		} catch(Exception e){
			LOG("auth callback()", 1, sERROR, e)
			//if(resp) parseAuthResponse(resp)
			eMsg= fail()
		}
	}else{
		LOG("callback() failed oauthState != state.oauthInitState", 1, sWARN)
		eMsg= fail()
	}
	render contentType: 'text/html', data: eMsg
}

static String success(){
	String message="""
	<p>Your AutoConnect Account is now connected!</p>
	<p>Close this window and click 'Done' to finish setup.</p>
	"""
	return connectionStatus(message)
}

static String fail(){
	String message="""
		<p>The connection could not be established!</p>
		<p>Close this window and click 'Done' to return to the menu.</p>
	"""
	return connectionStatus(message)
}

static String connectionStatus(String message, Boolean close=false){
	String redirectHtml= close ? """<script>document.getElementsByTagName('html')[0].style.cursor = 'wait';setTimeout(function(){window.close()},2500);</script>""" : sBLANK
	/*String redirectHtml=sBLANK
	if(redirectUrl){
		redirectHtml="""
			<meta http-equiv="refresh" content="3; url=${redirectUrl}" />
		"""
	} */
	String hubIcon='https://raw.githubusercontent.com/SANdood/Icons/master/Hubitat/HubitatLogo.png'

	String html="""
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=640">
<title>Husqvarna connection</title>
<style type="text/css">
		@font-face {
				font-family: 'Swiss 721 W01 Thin';
				src: url('https://s3.amazonaws.com/smartapp-icons/Partner/fonts/swiss-721-thin-webfont.eot');
				src: url('https://s3.amazonaws.com/smartapp-icons/Partner/fonts/swiss-721-thin-webfont.eot?#iefix') format('embedded-opentype'),
						url('https://s3.amazonaws.com/smartapp-icons/Partner/fonts/swiss-721-thin-webfont.woff') format('woff'),
						url('https://s3.amazonaws.com/smartapp-icons/Partner/fonts/swiss-721-thin-webfont.ttf') format('truetype'),
						url('https://s3.amazonaws.com/smartapp-icons/Partner/fonts/swiss-721-thin-webfont.svg#swis721_th_btthin') format('svg');
				font-weight: normal;
				font-style: normal;
		}
		@font-face {
				font-family: 'Swiss 721 W01 Light';
				src: url('https://s3.amazonaws.com/smartapp-icons/Partner/fonts/swiss-721-light-webfont.eot');
				src: url('https://s3.amazonaws.com/smartapp-icons/Partner/fonts/swiss-721-light-webfont.eot?#iefix') format('embedded-opentype'),
						url('https://s3.amazonaws.com/smartapp-icons/Partner/fonts/swiss-721-light-webfont.woff') format('woff'),
						url('https://s3.amazonaws.com/smartapp-icons/Partner/fonts/swiss-721-light-webfont.ttf') format('truetype'),
						url('https://s3.amazonaws.com/smartapp-icons/Partner/fonts/swiss-721-light-webfont.svg#swis721_lt_btlight') format('svg');
				font-weight: normal;
				font-style: normal;
		}
		.container {
				width: 90%;
				padding: 4%;
				/*background: #eee;*/
				text-align: center;
		}
		img {
				vertical-align: middle;
		}
		p {
				font-size: 2.2em;
				font-family: 'Swiss 721 W01 Thin';
				text-align: center;
				color: #666666;
				padding: 0 40px;
				margin-bottom: 0;
		}
		span {
				font-family: 'Swiss 721 W01 Light';
		}
</style>
</head>
<body>
		<div class="container">
				<img src="https://raw.githubusercontent.com/imnotbob/autoMower/master/images/husqvarna-logo.png" alt="ecobee icon" />
				<img src="https://s3.amazonaws.com/smartapp-icons/Partner/support/connected-device-icn%402x.png" alt="connected device icon" />
				<img src="${hubIcon}" alt="Hubitat logo" />
				${message}
		</div>
	${redirectHtml}
</body>
</html>
""".toString()
	return html
}


static String myObj(obj){
	if(obj instanceof String){return 'String'}
	else if(obj instanceof Map){return 'Map'}
	else if(obj instanceof List){return 'List'}
	else if(obj instanceof ArrayList){return 'ArrayList'}
	else if(obj instanceof Integer){return 'Int'}
	else if(obj instanceof BigInteger){return 'BigInt'}
	else if(obj instanceof Long){return 'Long'}
	else if(obj instanceof Boolean){return 'Bool'}
	else if(obj instanceof BigDecimal){return 'BigDec'}
	else if(obj instanceof Float){return 'Float'}
	else if(obj instanceof Byte){return 'Byte'}
	else if(obj instanceof ByteArrayInputStream){return 'ByteArrayInputStream'}
	else{ return 'unknown'}
}



Boolean weAreLost(String msgH, String meth){
	String msg
	msg= sBLANK
	if(!(String)state.authToken){
		apiLost(msgH+"weAreLost() found no auth token, called by ${meth}")
	}
	if(apiConnected() == sLOST){
		msg += "found connection lost to husqvarna | "
		if( refreshAuthToken(meth) ){
			msg += " - Was able to recover the lost connection. Please ignore any notifications received. | "
			LOG(msgH+msg, 4, sINFO)
		}else{
			msg += " - Unable to refresh token and get mowers do to loss of API Connection. Please ensure you are authorized."
			LOG(msgH+msg, 1, sERROR)
			return true
		}
	}
	return false
}



// Get the list of mowers for use in the settings pages
Map<String,String> getAutoMowers(Boolean frc=false, String meth="followup", Boolean isRetry=false){
	String msgH="getAutoMowers(force: $frc, calledby: $meth, isRetry: $isRetry) | "

	if(debugLevel(4)){ LOG(msgH+"====> entered ",4,sTRACE) }
	else LOG(msgH, 3,sTRACE)

	if(weAreLost(msgH, 'getAutoMowers')){
		return null
	}

	Map<String,String> res
	res=[:]

	String cached,msg
	cached=sBLANK
	Boolean skipIt
	skipIt=false
	Boolean myfrc=(!state.mowerData || !state.mowersWithNames)
	Integer lastU=getLastTsValSecs("getAutoUpdDt") // last attempt
	if( (frc && lastU < 60)){ skipIt=true }
	if( (!frc && lastU < 150) ){ skiptIt=true } // related to getMinMinsBtwPolls

	Map<String,String> mowers
	mowers=[:]
	Map mowersLocation
	mowersLocation=[:]

	msg=sBLANK

	if(myfrc || !skipIt){
		updTsVal("getAutoUpdDt")
		Map deviceListParams=[
			uri: getMowerApiEndpoint() +"/mowers",
			headers: [
				"Content-Type": "application/vnd.api+json",
				"Authorization": "Bearer ${(String)state.authToken}",
				"Authorization-Provider": "husqvarna",
				"X-Api-Key":getHusqvarnaApiKey()
			],
			query: null,
			timeout: 30
		]
		if(debugLevel(4)){
			msg+="http params -- ${deviceListParams} "
		}
		msg +="HTTPGET "
		if(msg){
			LOG(msgH + msg, 3, sTRACE)
			msg=sBLANK
		}

		Boolean exitout
		exitout=false
		try{
			httpGet(deviceListParams){ resp ->
				LOG(msgH + "httpGet() ${resp.status} Response", 4, sTRACE)
				String rdata
				Map adata
				if(resp){
					rdata=resp.data.text // need to save first time since it is a ByteArrayInputStream
					if(rdata) adata=(Map)new JsonSlurper().parseText(rdata)
				}
				if(resp && resp.isSuccess() && resp.status == 200 && adata){

					List<Map> ndata=((List<Map>)adata.data)?.findAll{ it.type == "mower" }

					state.numAvailMowers=((List<Map>)ndata)?.size() ?: 0

					Map<String, Map> mdata=[:]
					ndata.each{ Map mower ->
						String dni=getMowerDNI((String) mower.id)
						mowers[dni]=getMowerDisplayName(mower)
						mowersLocation[dni]=getMowerLocation(mower)
						mdata[dni]=mower
					}
					state.mowerData=mdata
					//log.debug "resp: ${state.mowerData}"
					updateLastPoll(false)
				}else{
					LOG(msgH + "httpGet() in else: http status: ${resp.status}", 1, sTRACE)
					//refresh the auth token
					if(resp.status == 500){ //} && resp.data?.status?.code == 14){
						if(!isRetry){
							LOG(msgH + "Refreshing auth_token!", 3, sTRACE)
							if(refreshAuthToken('getAutoMowers')){
								res= getAutoMowers(frc, meth, true)
							}
						}
					}else{
						LOG(msgH + "Other error. Status: ${resp.status} Response data: ${rdata} ", 1, sERROR)
					}
					exitout=true
				}
			}
		} catch(Exception e){
			LOG(msgH + "___exception", 1, sERROR, e)
			if(!isRetry){
				Boolean a= refreshAuthToken('getAutoMowers')
			}
			exitout=true
		}
		if(exitout) return res
		state.mowersWithNames=mowers
		state.mowersLocation=mowersLocation
	}else{
		mowers= state.mowersWithNames
		mowersLocation=state.mowersLocation
		cached="cached "
	}
	msg += cached+"mowersWithNames: ${mowers}, locations: ${mowersLocation}"
	LOG(msgH+msg, 4, sTRACE)
	return (mowers) ? mowers.sort{ it.value } : null
}


/*
 * max 1 command per second
 * Commands are queued at Husqvarna, and executed when mower checks in
 */
Boolean sendCmdToHusqvarna(String mowerId, Map data, Boolean isRetry=false, String uriend='actions'){
	String msgH= "sendCmdToHusqvarna(mower: $mowerId, data: $data, isRetry: $isRetry uriend: $uriend) | "

	Boolean ok= (mowerId && mowerId in (List<String>)settings.mowers)
	if(!ok){
		LOG(msgH + "mower not enabled in settings: $settings.mowers", 1, sERROR)
		return false
	}

	if(debugLevel(4)) LOG(msgH + "===> entered", 4, sTRACE)
	else LOG(msgH, 3,sTRACE)

	if(weAreLost(msgH, 'sendCmdToHusqvarna')){
		return false
	}

	Map deviceListParams=[
		uri: getMowerApiEndpoint() +"/mowers"+"/${mowerId}/${uriend}",
		headers: [
			"Content-Type": "application/vnd.api+json",
			"Authorization": "Bearer ${(String)state.authToken}",
			"Authorization-Provider": "husqvarna",
			"X-Api-Key":getHusqvarnaApiKey()
		],
		query: null,
		body: new JsonOutput().toJson(data),
		timeout: 30
	]
	String msg
	msg= sBLANK
	if(debugLevel(4)){
		msg+="http params -- ${deviceListParams} "
	}
	msg +="HTTPPOST "
	if(msg){
		LOG(msgH + msg, 2, sTRACE)
		msg=sBLANK
	}

	Boolean res
	res=false
	try{
		httpPost(deviceListParams){ resp ->
			String rdata
			/*
			Map adata
			if(resp){
				rdata=resp.data.text // need to save first time since it is a ByteArrayInputStream
				if(rdata) adata=(Map)new JsonSlurper().parseText(rdata)
			}*/
			if(resp && resp.isSuccess() && resp.status >= 200 && resp.status <= 299){
				res=true
				LOG(msgH + "httpPost() ${resp.status} Response $res", 2, sTRACE)
				runIn(85, sPOLL, [overwrite: true]) // give time for command to complete; then get new status
			}else{
				LOG(msgH + "httpPost() in else: http status: ${resp.status}", 1, sTRACE)
				//refresh the auth token
				if(resp.status == 500){ //} && resp.data?.status?.code == 14){
					//LOG(msgH + "Storing the failed action to try later", 1, sTRACE)
					//state.action="getAutoMowers"
					if(!isRetry){
						LOG(msgH + "Refreshing auth_token!", 3, sTRACE)
						if(refreshAuthToken('sendCmdToHusqvarna')) res= sendCmdToHusqvarna(mowerId, data, true,uriend)
					}
				}else{
					LOG(msgH + "Other error. Status: ${resp.status} Response data: ${rdata} $res", 1, sERROR)
				}
			}
		}
	} catch(Exception e){
		LOG(msgH + "___exception $res", 1, sERROR, e)
		//state.action="getAutoMowers"
		if(!isRetry){
			Boolean a= refreshAuthToken('sendCmdToHusqvarna')
		}
	}
	return res
}

Boolean sendSettingToHusqvarna(String mowerId, Map data, Boolean isRetry=false){
	return sendCmdToHusqvarna(mowerId, data, isRetry,'settings')
}

Boolean sendScheduleToHusqvarna(String mowerId, Map data, Boolean isRetry=false){
	return sendCmdToHusqvarna(mowerId, data, isRetry,'calendar')
}

Map getMowerMap(String tid){
	if(tid){
		String dni=getMowerDNI(tid)
		Map<String,Map>mowerMap=(Map<String,Map>)state.mowerData
		if(dni && mowerMap){
			return mowerMap[dni]
		}
	}
	return null
}

String getMowerName(String tid){
	// Get the name for this mower
	String DNI=getMowerDNI(tid)
	Map<String,String> mowersWithNames=state.mowersWithNames
	String mowerName
	mowerName=(mowersWithNames?.containsKey(DNI)) ? mowersWithNames[DNI] : sBLANK
	if(mowerName == sBLANK){ mowerName=getChildDevice(DNI)?.displayName } // better than displaying 'null' as the name
	return mowerName
}

static String getMowerDNI(String tid){
	return tid
	//return 'autoconnect-mower-' + ([app.id.toString(), tid].join('.'))
}

static String getMowerDisplayName(Map mower){
	String nm=(String)mower?.attributes?.system?.name ?: 'Name not found'
	return nm + ' - ' + getMowerModelName(mower)
}

static String getMowerModelName(Map mower){
	return (String)mower?.attributes?.system?.model ?: "Model not found"
}

static String getMowerLocation(Map mower){
	if((String)mower?.attributes?.mower?.mode && (String)mower?.attributes?.mower?.activity){
		return (String)mower?.attributes?.mower?.mode+sSPACE+(String)mower?.attributes?.mower?.activity
	}
	return "location not found??"
}

void settingUpdate(String name, value, String type=sNULL){
	if(name && type){
		app.updateSetting(name, [type: type, value: value])
	}else if(name && !type){ app.updateSetting(name, value) }
}

void settingRemove(String name){
	LOG("settingRemove($name)...",4, sTRACE)
	if(name && settings.containsKey(name)){ app?.removeSetting(name) }
}

void cleanupStates(){
	LOG("Cleaning up states", 3, sTRACE)

	state.remove('needPrograms')

	remTsVal('getAutoUpdDt')
	state.remove('timeSendPush')
	state.remove('oauthInitState')
	state.remove('lastLOGerror')
	state.remove('LastLOGerrorDate')
	state.remove('sunriseTime')
	state.remove('sunsetTime')
	state.remove('timeOfDay')
	state.remove('initializedEpic')
	state.remove('action')

	state.remove('statLocation')
	state.remove('dbg_lastSunriseEvent')
	state.remove('dbg_lastSunriseEventDate')
	state.remove('dbg_lastSunsetEvent')
	state.remove('dbg_lastSunsetEventDate')

/*	state.remove("pollingInterval")
	settingRemove('arrowPause')
	state.remove('audio') */
}

Boolean createChildrenMowers(){
	Boolean result
	result=true
	Integer ccnt,fnd
	ccnt=0
	fnd=0
	LOG("createChildrenMowers() entered: mowers=${(List<String>)settings.mowers}", 4, sTRACE)
	// Create the child Mower Devices
	List devices=((List<String>)settings.mowers).collect{ dni ->
		def d=getChildDevice(dni)
		if(!d){
			try{
				d=addChildDevice(myNamespace, getAutoMowerName(), dni, ((List)location.hubs)[0]?.id, ["label":"Mower: ${state.mowersWithNames[dni]}", completedSetup:true])
			} catch(Exception e){
				if("${e}".startsWith("com.hubitat.app.exception.UnknownDeviceTypeException")){
					LOG("You MUST add the ${getAutoMowerName()} Device Handler to the IDE BEFORE running the setup.", 1, sERROR, e)
					state.runTestOnce=null
					result=false
					return false
				}
			}
			ccnt += 1
			LOG("created ${d.displayName} with id ${dni}", 4, sTRACE)
		}else{
			fnd += 1
			LOG("found ${d.displayName} with id ${dni} already exists", 4, sTRACE)
		}
		return d
	}
	if(result) LOG("Created ($ccnt) / Updated ($fnd) / Total: ${devices.size()} mowers", 4, sTRACE)
	return result
}
/*
String getChildAppName(String childId){
	def child=getChildApps().find{ it.id.toString() == childId }
	return child ? (cleanAppName((String)child.label?:(String)child.name)) : sBLANK
} */

static String cleanAppName(String name){
	if(name){
		String cleanName
		Integer idx=name.indexOf('<span')
		return ((idx > 0) ? name.substring(0, idx) : name).trim()
	}
	return sNULL
}

// NOTE: For this to work correctly
void deleteUnusedChildren(){
	LOG("deleteUnusedChildren() entered", 5, sTRACE)

	// Always make sure that the dummy devices were deleted
	removeChildDevices((List)getAllChildDevices(), true)		// Delete dummy devices

	if(((List<String>)settings.mowers)?.size() == 0){
		// No mowers, need to delete all children
		LOG("Deleting All My Children!", 0, sWARN)
		removeChildDevices((List)getAllChildDevices(), false)
	}else{
		// Only delete those that are no longer in the list
		// This should be a combination of any removed mowers and any removed sensors
		List allMyChildren=(List)getAllChildDevices()
		LOG("These are currently all of my children: ${allMyChildren}", 4, sDEBUG)

		// Don't delete any devices that are configured in settings (mowers)
		List childrenToKeep=((List<String>)settings.mowers ?: []) + [ getSocketDNI() ] //socket
		LOG("These are the children to keep around: ${childrenToKeep}", 4, sTRACE)

		List childrenToDelete=allMyChildren.findAll{ !childrenToKeep.contains(it.deviceNetworkId) }
		if(childrenToDelete.size() > 0){
			LOG("Ready to delete these devices: ${childrenToDelete}", 0, sWARN)
			childrenToDelete?.each{ deleteChildDevice(it.deviceNetworkId) }
		}
	}
}

void scheduledWatchdog(evt=null, Boolean local=false, String meth="schedule/runin"){
	String msgH="scheduledWatchdog() | "
	String evtStr=evt ? "${evt.name}:${evt.value}" : 'null'
	String msg
	msg="event: (${evtStr}) | local (${local}) | by ${meth} | "
	Boolean debugLevelFour=debugLevel(4)
	if(debugLevelFour){ LOG(msgH+msg, 4, sTRACE); msg=sBLANK }

	Long oldLast
	oldLast=state.lastScheduledWatchdog
	String oldLastS
	oldLastS=state.lastScheduledWatchdogDate

	// Check to see if we have called too soon
	if(!state.lastScheduledWatchdog){
		oldLast= wnow() - 3600001L
		oldLastS= sNULL
	}

	state.lastScheduledWatchdog=wnow()
	state.lastScheduledWatchdogDate=getTimestamp()

	Long timeSinceLastWatchdog= Math.round((wnow() - oldLast) / 60000L)
	if( timeSinceLastWatchdog < 2L ){
		msg += "It has only been ${timeSinceLastWatchdog*60} seconds since last call. Exiting"
		if(debugLevelFour) LOG(msgH+msg, 4, sTRACE)
		return
	}

	// check if token needs to be refreshed
	Long texp= (Long)state.authTokenExpires
	Long timeBeforeExpiry=texp ? texp - wnow() : 0L
	if(timeBeforeExpiry < 1800000L){
		msg += "Calling refreshToken | timeBeforeExpiry: ${timeBeforeExpiry} | "
		if(debugLevelFour){ LOG(msgH+msg, 4, sTRACE); msg=sBLANK }
		if( !refreshAuthToken('scheduledWatchdog') ){
			return
		}
	}

	if(weAreLost(msgH, 'scheduledWatchdog')){
		msg += "exiting - no connection"
		if(debugLevelFour){ LOG(msgH+msg, 4, sTRACE); msg=sBLANK }
		return
	}

	if(msg && debugLevelFour){ LOG(msgH+msg, 4, sTRACE); msg=sBLANK }
	checkPolls(msgH)

	// Only update the Scheduled timestamp if run by a timer (schedule or runIn)
	//if( (evt==null && !local) || !oldLast || !oldLastS ){

	if(wnow() > oldLast+(3600000L*2) ){
		// do a forced update every other hour, just because (e.g., forces Hold Status to update completion date string)
		forceNextPoll()
		msg += "forcing device update | "
	}

	if(msg && debugLevelFour) LOG(msgH+msg, 4, sTRACE)
}

void checkPolls(String msgH, Boolean apiOk=true, Boolean frc=false){
	Boolean haveMowers=(((List<String>)settings.mowers)?.size() > 0)
	if(apiOk && haveMowers){
		chkRestartSocket(false)
		if(frc) LOG("Spawning the poll scheduled event. (mowers.size(): ${((List<String>) settings.mowers)?.size()})", 2, sTRACE)
		if(frc || !isDaemonAlive(sPOLL, msgH)){
			LOG(msgH + "rescheduling poll daemon", 1, sTRACE); spawnDaemon(sPOLL, !frc)
		}
	}else{
		unschedule('pollScheduled')
		if(frc && !haveMowers) LOG(msgH+"Not starting poll daemon; there are no mowers currently selected for use", 1, sWARN)
	}
	if(frc || !isDaemonAlive("watchdog", msgH)){ LOG(msgH+"rescheduling watchdog daemon",1,sTRACE); spawnDaemon("watchdog", !frc) }
}

Boolean isDaemonAlive(String daemon="all", String msgI){
	String msgH="isDaemonAlive(${daemon}, calledby: ${msgI}) | "
	String msg
	msg=sBLANK
	Boolean debugLevelFour=debugLevel(4)
	List<String> daemonList=[sPOLL, "watchdog", "all"]

	Boolean result
	result=true

	//if(debugLevelFour) LOG("isDaemonAlive() - wnow() == ${wnow()} for daemon (${daemon})", 1, sTRACE)

	if(daemon == sPOLL || daemon == "all"){
		Integer pollingInterval=gtPollingInterval()
		Map resM=checkT(sPOLL, (Long)state.lastScheduledPoll, pollingInterval)
		msg += resM.msg ?: sBLANK
		result=resM.res && result
	}

	if(daemon == "watchdog" || daemon == "all"){
		Map resM=checkT('watchdog', (Long)state.lastScheduledWatchdog, iWATCHDOGINTERVAL)
		msg += resM.msg ?: sBLANK
		result=resM.res && result
	}

	if(!daemonList.contains(daemon) ){
		msg += " - Unknown daemon: ${daemon} received. Do not know how to check this daemon."
		LOG(msgH+msg, 1, sERROR)
		result=false
		return result
	}
	msg += " - result is ${result}"
	if(result && debugLevelFour) LOG(msgH+msg, 4, sTRACE)
	if(!result) LOG(msgH+msg, 1, sWARN)
	return result
}

Map checkT(String typ, Long lVal, Integer intervalMins){
	Boolean result
	result=true
	Long lastScheduled=lVal
	Long timeSinceLastMins=!lastScheduled ? 1000L : Math.round(((wnow() - lastScheduled) / 60000))
	String msg
	msg=sBLANK
	msg += "Checking daemon ${typ} | "
	msg += "Time since last ${timeSinceLastMins} mins -- lastScheduled == ${lastScheduled} | "

	Integer maxPoll= Math.max(intervalMins,getMinMinsBtwPolls())
	if( timeSinceLastMins >= maxPoll+2) result=false
	msg += !result ? "NOT RUNNING | " : sBLANK
	return [res: result, msg: msg]
}

Boolean spawnDaemon(String idaemon="all", Boolean unsched=true){
	String daemon
	daemon=idaemon
	String msgH="spawnDaemon(${daemon}, $unsched) | "
	String msg
	msg=sBLANK
	Boolean debugLevelFour=debugLevel(4)
	List<String> daemonList=[sPOLL, "watchdog", "all"]

	daemon=daemon.toLowerCase()
	Boolean result
	result=true

	if(daemon == sPOLL || daemon == "all"){
		Integer pollingInterval
		pollingInterval=gtPollingInterval()
		//options:["6", "10", "15", "30", "60"])
		if(pollingInterval>30) pollingInterval=60
		msg += " - Performing seance for daemon 'poll' interval ${pollingInterval}"
		try{
			if(unsched){ unschedule('pollScheduled') }
			"runEvery${pollingInterval}Minute${pollingInterval!=1?'s':sBLANK}"('pollScheduled')

			if(unsched){ // Only poll now if we were recovering - otherwise whoever called will handle the poll (as in initialize())
				if(debugLevelFour) LOG(msgH+msg+' calling pollScheduled', 4, sTRACE)
				pollScheduled('spawnDaemon')
			}
		} catch(Exception e){
			msg += " - Exception when performing spawn for ${daemon}."
			LOG(msgH+msg, 1, sERROR, e)
			result=false
			return result
		}
	}

	if(daemon == "watchdog" || daemon == "all"){
		msg += " - Performing seance for daemon 'watchdog' interval ${iWATCHDOGINTERVAL}"
		try{
			if(unsched){ unschedule("scheduledWatchdog") }
			"runEvery${iWATCHDOGINTERVAL}Minutes"("scheduledWatchdog")
		} catch(Exception e){
			msg += " - Exception when performing spawn for ${daemon}."
			LOG(msgH+msg, 1, sERROR, e)
			result=false
			return result
		}
		scheduledWatchdog()
	}

	if(!daemonList.contains(daemon) ){
		msg += " - Unknown daemon: ${daemon} received. Do not know how to check this daemon."
		LOG(msgH+msg, 1, sERROR)
		result=false
		return result
	}
	msg += " - result is ${result}"
	if(result && debugLevelFour) LOG(msgH+msg, 4, sTRACE)
	if(!result) LOG(msgH+msg, 1, sWARN)
	return result
}


Long gtLastDataUpd(){
	Long last
	last= Math.max( ((Long)state.lastPollWS ?: 0L),
					((Long)state.lastPoll ?: 0L) )
}

void clearLastPolls(){
	state.remove('lastPoll')
	state.remove('lastPollDate')
	state.remove('lastPollWS')
	state.remove('lastPollWSDate')
}

void updateLastPoll(Boolean isWS=false){
	if(!isWS){
		state.lastPoll=wnow()
		state.lastPollDate=getTimestamp()

	}else{
		state.lastPollWS=wnow()
		state.lastPollWSDate=getTimestamp()
	}
}

void poll(Boolean isSched=false){
	LOG("poll()", 3, sTRACE)
	Boolean a= pollChildren(sNULL,false,isSched)
}

// Called by scheduled() event handler
void pollScheduled(String caller="runIn/Schedule"){
	LOG("pollScheduled(caller: $caller)", 3, sTRACE)
	state.lastScheduledPoll=wnow()
	state.lastScheduledPollDate=getTimestamp()
	poll(true)
}

void forceNextPoll(){
	Map updatesLog=state.updatesLog
	updatesLog.forcePoll=true
	state.updatesLog=updatesLog
}

// what child devices call on refresh()
void pollFromChild(String deviceId=sBLANK,Boolean force=false){
	LOG("pollFromChild()", 3, sTRACE)
	Boolean a= pollChildren(deviceId, force,false)
}

Boolean pollChildren(String deviceId=sBLANK,Boolean force=false, Boolean isSched=true){
	Boolean result
	result=false
	String msgH="pollChildren(device: $deviceId, force: $force) | "
	// Prevent multiple concurrent poll cycles
	if((Boolean)state.inPollChildren){
		Long skipTime=(Long)state.skipTime ?: wnow()
		if((Long)state.skipTime != skipTime) state.skipTime=skipTime
		// Give the already running poll 20/25 seconds to complete
		if((wnow()-skipTime) < 25000L ){
			// Already/still polling, capture the arguments and skip this poll request
			if(force) forceNextPoll()

			LOG(msgH+"prior poll not finished, skipping...")
			return result
		}
	}
	state.skipTime=null
	state.remove('skipTime')
	state.inPollChildren=true

	String version=getVersionLabel()
	LOG(msgH+"Checking for updates...",4,sTRACE)
	if(state.versionLabel != version){
		LOG(msgH+"Code updated: ${version} - re-initializing",2,sTRACE)
		state.versionLabel=version
		state.inPollChildren=false
		state.remove('inPollChildren')
		updated()
		return result
	}

	Boolean debugLevelFour=debugLevel(4)

	Long last= gtLastDataUpd()

	Long aa=wnow() - last
	Integer minPoll= isSched ? Math.max(gtPollingInterval(),getMinMinsBtwPolls()) : Math.min(gtPollingInterval(),getMinMinsBtwPolls())
	Long delta= (minPoll*60000L) - aa
	Boolean tooSoon=( delta > 60000L)

	if(tooSoon){

		if(debugLevel(4)){
			LOG(msgH+"Too soon poll request, deferring...recent: ${aa/60000L} mins last: $last desired: ${minPoll}",2,sTRACE)
			LOG(msgH+"=====> state.lastPoll RUN (${state.lastPoll}) now(${wnow()}) state.lastPollDate(${state.lastPollDate})", 2, sTRACE)
//			LOG(msgH+"=====> state.lastScheduledPoll RUN (${state.lastScheduledPoll}) now(${wnow()}) state.lastScheduledPollDate(${state.lastScheduledPollDate})", 2, sTRACE)
			LOG(msgH+"=====> state.lastPollWS RUN (${state.lastPollWS}) now(${wnow()}) state.lastPollWSDate(${state.lastPollWSDate})", 2, sTRACE)
		}

		state.inPollChildren=false
		state.remove('inPollChildren')
		Integer secs= Math.round(delta/1000L).toInteger() - 20
		runIn(secs, sPOLL, [overwrite: true]) // give time for command to complete; then get new status
		return result
	}

	// Start the new poll cycle
	state.pollAutoConnectAPIStart=wnow()
//	if(debugLevelFour) LOG(msgH, 1, sTRACE)
	//String mowersToPoll

	Map updatesLog=state.updatesLog
	if(force || updatesLog.forcePoll){
		updatesLog.forcePoll=true
		state.updatesLog=updatesLog
	}
	Boolean forcePoll
	forcePoll=(Boolean)updatesLog.forcePoll

	if(weAreLost(msgH, 'pollChildren')){
		state.inPollChildren=false
		state.remove('inPollChildren')
		return result
	}

	checkPolls(msgH)

	Map foo=getAutoMowers(forcePoll,"pollChildren")
	if(foo!=null){
		updatesLog.forcePoll=false
		state.updatesLog=updatesLog
		if(((List<String>)settings.mowers)?.size() < 1){
			LOG(msgH+"Nothing to poll as there are no mowers currently selected", 1, sWARN)
			state.inPollChildren=false
			state.remove('inPollChildren')
			return result
		}

		result=updateMowerChildren()

	}else{ LOG(msgH+"no data",2,sWARN) }

	state.inPollChildren=false
	state.remove('inPollChildren')
	return result
}

/**
 * Send data to mower device
 */
Boolean updateMowerChildren(){
	String msgH="updateMowerChildren | "
	Boolean result
	result=false
	((List<String>)settings.mowers)?.each{String mower ->
		List<Map> flist=[]
		Map srcMap=getMowerMap(mower)
		if(srcMap){
			String dbg=settings.debugLevel == null ? "2" : settings.debugLevel
			String apiConnection=apiConnected()
			String slastPoll=(debugLevel(4)) ? "${apiConnection} @ ${formatDt(new Date(gtLastDataUpd()))}" : (apiConnection==sFULL) ? 'Succeeded' : (apiConnection==sWARN) ? 'Timed Out' : 'Failed'

			Boolean moving=(String)srcMap.attributes.mower.activity in [ 'MOWING', 'GOING_HOME', 'LEAVING' ]
			Boolean onMain=(String)srcMap.attributes.mower.activity in [ 'CHARGING', 'PARKED_IN_CS' ]
			Boolean stuck=( (String)srcMap.attributes.mower.activity in [ 'STOPPED_IN_GARDEN' ] ||
					(String)srcMap.attributes.mower.state in [ 'PAUSED', 'OFF', 'STOPPED', 'ERROR', 'FATAL_ERROR', 'ERROR_AT_POWER_UP' ] )
			Boolean parked=( (String)srcMap.attributes.mower.activity in [ 'PARKED_IN_CS', 'CHARGING' ])
			Boolean hold=( parked && (String)srcMap.attributes.mower.state in [ 'RESTRICTED' ])
			Boolean holdIndefinite=( hold && srcMap.attributes.planner.nextStartTimestamp == 0)
			Boolean holdUntilNext=( hold && srcMap.attributes.planner.nextStartTimestamp != 0)
			def collisions
			collisions= srcMap.attributes.statistics.numberofCollisions
			if(collisions == null) collisions= 0

			flist << ['name':	(String)srcMap.attributes.system.name ] //STRING
			flist << ['id':	srcMap.id ] //STRING
			flist << ['model':	srcMap.attributes.system.model ] //STRING
			flist << ['serialNumber':	srcMap.attributes.system.serialNumber.toString() ] //STRING
			flist << ['mowerConnected':	srcMap.attributes.metadata.connected] // TRUE or FALSE
			flist << ['mowerTimeStamp'	: srcMap.attributes.metadata.statusTimestamp] // LAST TIME connected (EPOCH LONG)
			flist << ['battery': srcMap.attributes.battery.batteryPercent] // Battery %

			flist << ['mowerStatus':	srcMap.attributes.mower.mode ] //MAIN_AREA, SECONDARY_AREA, HOME, DEMO, UNKNOWN
			flist << ['mowerActivity': srcMap.attributes.mower.activity] //UNKNOWN, NOT_APPLICABLE, MOWING, GOING_HOME, CHARGING, LEAVING, PARKED_IN_CS, STOPPED_IN_GARDEN
			String mst= srcMap.attributes.mower.state //UNKNOWN, NOT_APPLICABLE, PAUSED, IN_OPERATION, WAIT_UPDATING, WAIT_POWER_UP, RESTRICTED,
						// OFF, STOPPED, ERROR, FATAL_ERROR, ERROR_AT_POWER_UP
			flist << ['mowerState':	mst]
			Boolean hasErr = mst in [ 'ERROR', 'FATAL_ERROR', 'ERROR_AT_POWER_UP' ]
			String errC=srcMap.attributes.mower.errorCode // STRING
			flist << ['errorCode':	errC ] // STRING
			flist << ['errorCodeS':	hasErr && errC!=sNULL && errCodes[errC]!=null ? errCodes[errC] : 'Not set' ] // STRING
			flist << ['errorTimeStamp': srcMap.attributes.mower.errorCodeTimestamp] // (EPOCH LONG)
			flist << ['plannerNextStart': srcMap.attributes.planner.nextStartTimestamp] // (EPOCH LONG)
			flist << ['plannerOverride'	: srcMap.attributes.planner.override.action] // Override Action

			flist << ['motion': moving ? 'active' : 'inactive']
			flist << ['powerSource': onMain ? 'mains' : 'battery'] // "battery", "dc", "mains", "unknown"
			flist << ['stuck': stuck]
			flist << ['parked': parked]
			flist << ['hold': hold]
			flist << ['holdUntilNext': holdUntilNext]
			flist << ['holdIndefinite': holdIndefinite]

			flist << ['cuttingHeight': srcMap.attributes.settings.cuttingHeight] // Level
			flist << ['headlight': srcMap.attributes.settings.headlight.mode]

			try {
				flist << ['numberOfChargingCycles': srcMap.attributes.statistics.numberOfChargingCycles]
				flist << ['numberOfCollisions': collisions]
				flist << ['totalChargingTime': srcMap.attributes.statistics.totalChargingTime / 3600]
				flist << ['totalCuttingTime': srcMap.attributes.statistics.totalCuttingTime / 3600]
				flist << ['totalRunningTime': srcMap.attributes.statistics.totalRunningTime / 3600]
				flist << ['totalSearchingTime': srcMap.attributes.statistics.totalSearchingTime / 3600]
				flist << ['cuttingBladeUsageTime': srcMap.attributes.statistics.cuttingBladeUsageTime / 3600]
			} catch(ignored){}

			flist << [apiConnected: apiConnection]
			flist << [lastPoll: slastPoll]
			flist << [debugLevel: dbg]

			def chld=getChildDevice(mower)
			if(chld){ chld.generateEvent(flist); result=true }
			else LOG(msgH+'Child device $mower not found', 1, sWARN)
		}else LOG(msgH+"no data from API for mower $mower", 3, sWARN)
	}
	return result

}

// This only updates a few states
void generateEventLocalParams(){

	Boolean dbg2= debugLevel(2)
	Boolean dbg4= debugLevel(4)
	String apiConnection=apiConnected()
	String slastPoll= dbg4 ? "${apiConnection} @ ${formatDt(new Date(gtLastDataUpd()))}" : (apiConnection==sFULL) ? 'Succeeded' : (apiConnection==sWARN) ? 'Timed Out' : 'Failed'
	String dbg= settings."debugLevel" == null ? "2" : settings."debugLevel"

	List<Map> data=[]
	data << [apiConnected: apiConnection]
	data << [lastPoll: slastPoll]
	data << ["debugLevel": dbg]

	String LOGtype= apiConnection==sLOST ? sERROR : (apiConnection==sWARN ? sWARN : sINFO)
	Integer lvl= apiConnection==sLOST ? 2 : (apiConnection==sWARN ? 2 : 4)
	Boolean a= lvl == 2 ? dbg2 : dbg4 // TODO THIS IS STRANGE INTELLIJ bug was debugLevel(lvl)
	if(a){
		LOG("Updating API status with ${data}${LOGtype==sWARN ? " - will retry" : ''}", lvl, LOGtype)
	}

	// Iterate over all the children
	((List<String>)settings.mowers)?.each{ String it ->
		getChildDevice(it)?.generateEvent(data)
	}
}

@Field static Map<String,String> errCodes=[
		"0":	"Unexpected error",
		"1":	"Outside working area",
		"2":	"No loop signal",
		"3":	"Wrong loop signal",
		"4":	"Loop sensor problem, front",
		"5":	"Loop sensor problem, rear",
		"6":	"Loop sensor problem, left",
		"7":	"Loop sensor problem, right",
		"8":	"Wrong PIN code",
		"9":	"Trapped",
		"10":	"Upside down",
		"11":	"Low battery",
		"12":	"Empty battery",
		"13":	"No drive",
		"14":	"Mower lifted",
		"15":	"Lifted",
		"16":	"Stuck in charging station",
		"17":	"Charging station blocked",
		"18":	"Collision sensor problem, rear",
		"19":	"Collision sensor problem, front",
		"20":	"Wheel motor blocked, right",
		"21":	"Wheel motor blocked, left",
		"22":	"Wheel drive problem, right",
		"23":	"Wheel drive problem, left",
		"24":	"Cutting system blocked",
		"25":	"Cutting system blocked",
		"26":	"Invalid sub-device combination",
		"27":	"Settings restored",
		"28":	"Memory circuit problem",
		"29":	"Slope too steep",
		"30":	"Charging system problem",
		"31":	"STOP button problem",
		"32":	"Tilt sensor problem",
		"33":	"Mower tilted",
		"34":	"Cutting stopped - slope too steep",
		"35":	"Wheel motor overloaded, right",
		"36":	"Wheel motor overloaded, left",
		"37":	"Charging current too high",
		"38":	"Electronic problem",
		"39":	"Cutting motor problem",
		"40":	"Limited cutting height range",
		"41":	"Unexpected cutting height adj",
		"42":	"Limited cutting height range",
		"43":	"Cutting height problem, drive",
		"44":	"Cutting height problem, curr",
		"45":	"Cutting height problem, dir",
		"46":	"Cutting height blocked",
		"47":	"Cutting height problem",
		"48":	"No response from charger",
		"49":	"Ultrasonic problem",
		"50":	"Guide 1 not found",
		"51":	"Guide 2 not found",
		"52":	"Guide 3 not found",
		"53":	"GPS navigation problem",
		"54":	"Weak GPS signal",
		"55":	"Difficult finding home",
		"56":	"Guide calibration accomplished",
		"57":	"Guide calibration failed",
		"58":	"Temporary battery problem",
		"59":	"Temporary battery problem",
		"60":	"Temporary battery problem",
		"61":	"Temporary battery problem",
		"62":	"Temporary battery problem",
		"63":	"Temporary battery problem",
		"64":	"Temporary battery problem",
		"65":	"Temporary battery problem",
		"66":	"Battery problem",
		"67":	"Battery problem",
		"68":	"Temporary battery problem",
		"69":	"Alarm! Mower switched off",
		"70":	"Alarm! Mower stopped",
		"71":	"Alarm! Mower lifted",
		"72":	"Alarm! Mower tilted",
		"73":	"Alarm! Mower in motion",
		"74":	"Alarm! Outside geofence",
		"75":	"Connection changed",
		"76":	"Connection NOT changed",
		"77":	"Com board not available",
		"78":	"Slipped - Mower has Slipped.Situation not solved with moving pattern",
		"79":	"Invalid battery combination - Invalid combination of different battery types.",
		"80":	"Cutting system imbalance	Warning",
		"81":	"Safety function faulty",
		"82":	"Wheel motor blocked, rear right",
		"83":	"Wheel motor blocked, rear left",
		"84":	"Wheel drive problem, rear right",
		"85":	"Wheel drive problem, rear left",
		"86":	"Wheel motor overloaded, rear right",
		"87":	"Wheel motor overloaded, rear left",
		"88":	"Angular sensor problem",
		"89":	"Invalid system configuration",
		"90":	"No power in charging station",
		"91":	"Switch cord problem",
		"92":	"Work area not valid",
		"93":	"No accurate position from satellites",
		"94":	"Reference station communication problem",
		"95":	"Folding sensor activated",
		"96":	"Right brush motor overloaded",
		"97":	"Left brush motor overloaded",
		"98":	"Ultrasonic Sensor 1 defect",
		"99":	"Ultrasonic Sensor 2 defect",
		"100":	"Ultrasonic Sensor 3 defect",
		"101":	"Ultrasonic Sensor 4 defect",
		"102":	"Cutting drive motor 1 defect",
		"103":	"Cutting drive motor 2 defect",
		"104":	"Cutting drive motor 3 defect",
		"105":	"Lift Sensor defect",
		"106":	"Collision sensor defect",
		"107":	"Docking sensor defect",
		"108":	"Folding cutting deck sensor defect",
		"109":	"Loop sensor defect",
		"110":	"Collision sensor error",
		"111":	"No confirmed position",
		"112":	"Cutting system major imbalance",
		"113":	"Complex working area",
		"114":	"Too high discharge current",
		"115":	"Too high internal current",
		"116":	"High charging power loss",
		"117":	"High internal power loss",
		"118":	"Charging system problem",
		"119":	"Zone generator problem",
		"120":	"Internal voltage error",
		"121":	"High internal temerature",
		"122":	"CAN error",
		"123":	"Destination not reachable",
		"124":	"Destination blocked",
		"125":	"Battery needs replacement",
		"126":	"Battery near end of life",
		"127":	"Battery problem",
		"128":	"Multiple reference stations detected",
		"129":	"Auxiliary cutting means blocked",
		"130":	"Imbalanced auxiliary cutting disc detected",
		"131":	"Lifted in link arm",
		"132":	"EPOS accessory missing",
		"133":	"Bluetooth com with CS failed",
		"134": "Invalid SW configuration",
		"135": "Radar problem",
		"136": "Work area tampered",
		"137": "High temperature in cutting motor, right",
		"138": "High temperature in cutting motor, center",
		"139": "High temperature in cutting motor, left",
		"141": "Wheel brush motor problem",
		"143": "Accessory power problem",
		"144": "Boundary wire problem",
		"145": "No correction data available",
		"147": "Cutting disc lost",
		"148": "Chassis collision",
		"701":	"Connectivity problem",
		"702":	"Connectivity settings restored",
		"703":	"Connectivity problem",
		"704":	"Connectivity problem",
		"705":	"Connectivity problem",
		"706":	"Poor signal quality",
		"707":	"SIM card requires PIN",
		"708":	"SIM card locked",
		"709":	"SIM card not found",
		"710":	"SIM card locked",
		"711":	"SIM card locked",
		"712":	"SIM card locked",
		"713":	"Geofence problem",
		"714":	"Geofence problem",
		"715":	"Connectivity problem",
		"716":	"Connectivity problem",
		"717": "SMS could not be sent",
		"724": "Communication circuit board SW must be updated "
]

static String toQueryString(Map m){
	return m.collect{ k, v -> "${k}=${URLEncoder.encode(v.toString())}" }.sort().join("&")
}

void retryHelper(){
	Boolean a=refreshAuthToken('retryHelper')
}

// returns false if token is not valid
Boolean refreshAuthToken(String meth, child=null){
	String msgH="refreshAuthToken(${meth}, $child) | "
	String msg
	msg=sBLANK
	Boolean debugLevelFour=debugLevel(4)
//	if(debugLevelFour) LOG('Entered refreshAuthToken()', 4, sTRACE)

	Long texp= (Long)state.authTokenExpires
	String aT= atomicState.authToken
	Long timeBeforeExpiry= texp && aT ? texp - wnow() : 0L
	Boolean tokenStillGood
	tokenStillGood=(timeBeforeExpiry > 2000L)
	msg += "Token is ${tokenStillGood ? "valid" : "invalid"} | texp: ${texp} | timeBeforeExpiry: ${timeBeforeExpiry} | authToken: ${aT} | "

	// check to see if token was recently refreshed
	Integer pollingIntrvMin=(gtPollingInterval()+1)*2
	if(timeBeforeExpiry > (pollingIntrvMin*60000L)){
		msg += "exiting, token expires in ${timeBeforeExpiry/1000} seconds"
		if(debugLevelFour) LOG(msgH+msg,4,sINFO)
		// Double check that the daemons are still running
		checkPolls(msgH)
		return tokenStillGood
	}

	msg += "Want to refresh token | "
	def rt= atomicState.refreshToken
	if(!rt || timeBeforeExpiry < 30L){
		state.authToken=sNULL
		tokenStillGood=false
		if(msg){ LOG(msgH + msg, 2, sTRACE); msg=sBLANK }
		apiLost(msgH+"No refresh Token (${rt}) or expired refresh token ${timeBeforeExpiry} ${texp} | CLEARED authToken due to no refreshToken or expired authToken")
	}else{
		msg +='Performing token refresh'
		Map rdata=[grant_type: 'refresh_token', client_id: getHusqvarnaApiKey(), refresh_token: "${rt}"]
		String data=rdata.collect{ String k,v -> encodeURIComponent(k)+'='+encodeURIComponent(v) }.join('&')
		Map refreshParams=[
			uri: getApiEndpoint()+"/token",
			query: null,
			contentType: "application/x-www-form-urlencoded",
			body: data,
			timeout: 30
		]

		if(debugLevelFour){
			msg +="refreshParams=${refreshParams} "
			msg += "OAUTH Token=state: ${aT} "
			msg += "Refresh Token=state: ${rt}  "
		}

		msg += "state.authTokenExpires=${texp}  ${formatDt(new Date(texp))} "
		if(msg){
			LOG(msgH + msg, 2, sTRACE) // 4
			msg=sBLANK
		}
		try{
			httpPost(refreshParams){ resp ->
				//if(debugLevelFour) LOG("Inside httpPost resp handling.", 1, sTRACE, null, child)
				if(resp && resp.isSuccess() && resp.status && (resp.status == 200)){
					if(debugLevelFour) LOG(msgH+'200 Response received - Extracting info.', 4, sTRACE, null, child ) // 4

//					parseAuthResponse(resp)
					String kk
					resp.data.each{ kk=it.key }
					Map ndata=(Map)new JsonSlurper().parseText(kk)
//					log.debug "ndata : ${ndata}"

					String oldAuthToken=aT
					if(oldAuthToken == ndata.access_token){
						LOG(msgH+'WARN: state.authToken did NOT update properly! This is likely a transient problem', 1, sWARN, null, child)
						return tokenStillGood
					}

					if(state.reAttempt){ state.reAttempt=0; state.remove('reAttempt') }
					if(state.inTimeoutRetry){ state.inTimeoutRetry=0; state.remove('inTimeoutRetry') }
					state.lastTokenRefresh=wnow()
					state.lastTokenRefreshDate=getTimestamp()

					Long tt=wnow() + (ndata.expires_in * 1000)
					if(debugLevelFour){ // 4
						msg += "Updated state.authTokenExpires=${tt} "
						msg += "Refresh Token=state: ${rt} == in: ${ndata.refresh_token} "
						msg += "OAUTH Token=state: ${aT} == in: ${ndata.access_token}"
						LOG(msgH+msg, 4, sTRACE, null, child)
					}
					state.authTokenExpires=tt
					state.refreshToken=ndata.refresh_token
					state.authToken=ndata.access_token
					//atomicState.authTokenExpires=tt
					atomicState.refreshToken=ndata.refresh_token
					atomicState.authToken=ndata.access_token
					tokenStillGood=true

					def dev= getSocketDevice()
					if(dev){
						dev.updateCookies(ndata.access_token)
						if(!(Boolean)dev.isSocketActive()){ dev.triggerInitialize() }
					}

					LOG("refreshAuthToken() - Success! Token expires in ${String.format("%.2f",ndata.expires_in/60)} minutes", 3, sINFO, null, child) // 3

					// Tell the children that we are once again connected to the AutoConnect API Cloud
					if(apiConnected() != sFULL){ apiRestored(false) }

					checkPolls(msgH)
				}else{
					LOG(msgH+"Failed ${resp.status} : ${resp.status.code}!", 1, sERROR, null, child)
				}
			}
		} catch(e){
			Integer maxAttempt
			maxAttempt=5
			if("${e}".contains("HttpResponseException")){
				LOG(msgH+"HttpResponseException occurred. StatusCode: ${e.statusCode}", 1, sERROR, e, child)
			}else if("${e}".contains("TimeoutException")){
				maxAttempt= 20
				// Likely bad luck and network overload, move on and let it try again
				LOG(msgH+"TimeoutException", 1, sWARN, e, child)
			}else{
				LOG(msgH + "Not Sure of issue", 1, sERROR, e, child)
			}
			Integer attempts
			attempts= (Integer)state.reAttempt
			attempts= attempts!=null ? attempts+1 : 1
			state.reAttempt=attempts
			if(attempts > maxAttempt || timeBeforeExpiry < 1L){
				state.authToken=sNULL
				tokenStillGood=false
				apiLost(msgH+"CLEARING AUTH TOKEN - Too many retries (${state.reAttempt - 1}) for token refresh, or expired auth token ${timeBeforeExpiry} ${state.authTokenExpires}")
				def dev= getSocketDevice()
				if(dev){
					dev.removeCookies(true)
				}
			}else{
				LOG(msgH+"Setting up runIn for refreshAuthToken", 2, sTRACE, null, child) // 4
				Integer retryFactor= attempts > 12 ? 12 : attempts
				runIn(iREATTEMPTINTERVAL*retryFactor, retryHelper, [overwrite: true])
				if(attempts > 3 && apiConnected() == sFULL){
					state.connected=sWARN
					updateMyLabel()
					generateEventLocalParams()
				}
			}
		}
	}

	return tokenStillGood
}

//String getServerUrl()			{ return getFullApiServerUrl() }
//String getShardUrl()			{ return getFullApiServerUrl()+"?access_token=${state.accessToken}" }

void LOG(String message, Integer level=3, String ilogType=sDEBUG, ex=null, child=null, Boolean event=false, Boolean displayEvent=true){
	String logType
	logType=ilogType
	if(logType == sNULL) logType=sDEBUG

	Integer dbgLevel=getIDebugLevel()
	if(logType == sERROR){
		String a=getTimestamp()
		state.lastLOGerror="${message} @ "+a
		state.LastLOGerrorDate=a
	}else{
		if(level > dbgLevel) return
	}

	if(!lLOGTYPES.contains(logType)){
		logerror("LOG() - Received logType (${logType}) which is not in the list of allowed types ${lLOGTYPES}, message: ${message}, level: ${level}")
		if(event && child){ debugEventFromParent(child, "LOG() - Invalid logType ${logType} (warn)") }
		logType=sDEBUG
	}

	String prefix
	prefix=sBLANK
	if( dbgLevel == 5 ){ prefix='LOG: ' }
	"log${logType}"("${prefix}${message}", ex)
	if(event){ debugEvent(message, displayEvent) }
	if(child){ debugEventFromParent(child, message+" (${logType})") }
}

private void logdebug(String msg, Exception ex=null){ log.debug logPrefix(msg, "purple") }
private void loginfo(String msg, Exception ex=null){ log.info sSPACE + logPrefix(msg, "#0299b1") }
private void logtrace(String msg, Exception ex=null){ log.trace logPrefix(msg, sCLRGRY) }
private void logwarn(String msg, Exception ex=null){ logexception(msg,ex,sWARN, sCLRORG) }
void logerror(String msg, Exception ex=null){ logexception(msg,ex,sERROR, sCLRRED) }

void logexception(String msg, Exception ex=null, String typ, String clr){
	String msg1=ex ? " Exception: ${ex}" : sBLANK
	log."$typ" logPrefix(msg+msg1, clr)
	String a
	try{
		if(ex) a=getExceptionMessageWithLine(ex)
	} catch(ignored){ }
	if(a) log."$typ" logPrefix(a, clr)
}

static String logPrefix(String msg, String color=sNULL){
	return span("AutoMower App (v" + getVersionNum() + ") | ", sCLRGRY) + span(msg, color)
}

static String span(String str, String clr=sNULL, String sz=sNULL, Boolean bld=false, Boolean br=false){ return str ? "<span ${(clr || sz || bld) ? "style='${clr ? "color: ${clr};" : sBLANK}${sz ? "font-size: ${sz};" : sBLANK}${bld ? "font-weight: bold;" : sBLANK}'" : sBLANK}>${str}</span>${br ? sLINEBR : sBLANK}" : sBLANK }

Integer getIDebugLevel(){
	String dbg=settings.debugLevel == null ? "2" : settings.debugLevel
	return (dbg.toInteger())
}

Boolean debugLevel(Integer level=3){
	return (getIDebugLevel() >= level)
}

void debugEvent(String message, Boolean displayEvent=false){
	Map results=[
		name: 'appdebug',
		descriptionText: message,
		displayed: displayEvent
	]
	if( debugLevel(4) ){ LOG("Generating AppDebug Event: ${results}", 3, sDEBUG) }
	sendEvent(results)
}

@SuppressWarnings('GrMethodMayBeStatic')
void debugEventFromParent(child, String message){
	if(child){ child.generateEvent([ [debugEventFromParent: message] ]) }
}

Boolean notifyNowOK(){
	Boolean modeOK=(List)settings.speakModes ? ((List)settings.speakModes && ((List)settings.speakModes).contains((String)location.mode)) : true
	Boolean timeOK=settings.speakTimeStart? myTimeOfDayIsBetween((Date)timeToday(settings.speakTimeStart), (Date)timeToday(settings.speakTimeEnd), new Date()) : true
	return (modeOK && timeOK)
}

private static Boolean myTimeOfDayIsBetween(Date ifromDate, Date itoDate, Date checkDate)	{
	Date fromDate,toDate
	fromDate=ifromDate
	toDate=itoDate
	if(toDate == fromDate){
		return false	// blocks the whole day
	}else if(toDate < fromDate){
		if(checkDate.before(fromDate)){
			fromDate -= 1
		}else{
			toDate += 1
		}
	}
	return (!checkDate.before(fromDate) && !checkDate.after(toDate))
}

// send both push notification and mobile activity feeds
void sendMessage(String notificationMessage){
	String msgH="sendMessage() | "
	// notification is sent to remind user no more than once per hour
	Long otsp=state.timeSendPush ?: null
	Boolean sendNotification=!(otsp && ((wnow() - otsp) < 3600000))

	String msg1
	msg1=sendNotification ? "Sending" : "Not sending"
	msg1+=" Notification Message: ${notificationMessage}"
	msg1+=" Last Notification Time: ${state.timeSendPush}"
	LOG(msgH+msg1, 2, sTRACE)

	if(sendNotification){
		Long ntsp
		ntsp=otsp
		String msgPrefix=state.appDisplayName + " at ${location.name}: "
		String msg=msgPrefix + notificationMessage
		Boolean addFrom=true // (msgPrefix && !msgPrefix.startsWith("From "))
		if(settings.notifiers){
			if( sendNotifications(msgPrefix, notificationMessage) ){
				ntsp=wnow()
			}
		}
		if((Boolean)settings.speak){
			if(notifyNowOK()){
				if(settings.speechDevices != null){
					settings.speechDevices.each{
						it.speak((addFrom?"From ":sBLANK) + msg )
					}
					ntsp=wnow()
				}
				if(settings.musicDevices != null){
					settings.musicDevices.each{
						it.setLevel( settings.volume )
						it.playText((addFrom?"From ":sBLANK) + msg )
					}
					ntsp=wnow()
				}
			}else LOG(msgH+"speak/music notification restricted", 2, sTRACE)
		}
		if(otsp != ntsp){
			state.timeSendPush=ntsp
		}else LOG(msgH+"settings did not have any message sent", 2, sTRACE)
	}
}

Boolean sendNotifications( String msgPrefix, String msg ){
	if(!settings.notifiers){
		LOG("sendNotifications(): no notifiers!",2,sWARN)
		return false
	}

	List echo=((List)settings.notifiers).findAll{ (it.deviceNetworkId.contains('|echoSpeaks|') && it.hasCommand('sendAnnouncementToDevices')) }
	List notEcho=echo ? (List)settings.notifiers - echo : (List)settings.notifiers
	List echoDeviceObjs=[]
	if((Boolean)settings.echoAnnouncements){
		if(echo?.size()){
			// Get all the Echo Speaks devices to speak at once
			echo.each{
				String deviceType=it.currentValue('deviceType') as String
				// deviceSerial is an attribute as of Echo Speaks device version 3.6.2.0
				String deviceSerial=(it.currentValue('deviceSerial') ?: it.deviceNetworkId.toString().split(/\|/).last()) as String
				echoDeviceObjs.push([deviceTypeId: deviceType, deviceSerialNumber: deviceSerial])
			}
			if(echoDeviceObjs?.size()){
				//NOTE: Only sends command to first device in the list | We send the list of devices to announce one and then Amazon does all the processing
				//def devJson=new groovy.json.JsonOutput().toJson(echoDeviceObjs)
				echo[0].sendAnnouncementToDevices(msg, (msgPrefix?:state.appDisplayName), echoDeviceObjs)	// , changeVol, restoreVol) }
			}
			// The rest get a standard deviceNotification
			if(notEcho.size()) notEcho*.deviceNotification(msg)
		}else{
			// No Echo Speaks devices
			settings.notifiers*.deviceNotification(msg)
		}
	}else{
		// Echo Announcements not enabled - just do deviceNotifications, but only if Do Not Disturb is not on
		if(echo?.size()) echo*.deviceNotification(msg)
		if(notEcho.size()) notEcho*.deviceNotification(msg)
	}
	return true
}

/*
void sendActivityFeeds(String notificationMessage){
	def devices=getChildDevices()
	devices.each{ child ->
		child.generateActivityFeedsEvent(notificationMessage) //parse received message from parent
	}
} */
/*
static String toJson(Map m){
	return JsonOutput.toJson(m)
} */

static String getTimestamp(){
	return new Date().format("yyyy-MM-dd HH:mm:ss z")
}

String apiConnected(){
	// values can be sFULL, sWARN, sLOST
	if(!(String)state.connected){ state.connected=sWARN; updateMyLabel(); return sWARN }else{ return (String)state.connected }
}

void apiRestored(Boolean chkP=true){
	state.connected=sFULL
	updateMyLabel()
	unschedule("notifyApiLostHelper")
	unschedule("notifyApiLost")
	state.lastScheduledPoll=null
	state.lastScheduledPollDate=sNULL
	if(chkP) checkPolls('apiRestored() ', true)
	generateEventLocalParams() // Update the connection status

	def dev= getSocketDevice()
	if(dev){
		if(!(Boolean)dev.isSocketActive()){ dev.triggerInitialize() }
	}
}

Map getDebugDump(){
	Map debugParams=[
			when:"${getTimestamp()}", whenEpoch:"${wnow()}",
			lastPollDate:"${state.lastPollDate}",
			lastPollWSDate: state.lastPollWSDate,
			lastScheduledPollDate:"${state.lastScheduledPollDate}",
			lastScheduledWatchdogDate:"${state.lastScheduledWatchdogDate}",
			lastTokenRefreshDate:"${state.lastTokenRefreshDate}",
			initializedEpoch:"${state.initializedEpoch}", initializedDate:"${state.initializedDate}",
			lastLOGerror:"${state.lastLOGerror}", authTokenExpires:"${(Long)state.authTokenExpires}"
	]
	return debugParams
}

void apiLost(String where="[where not specified]"){
	if(apiConnected() == sLOST){
		LOG("apiLost($where) - already in lost state.", 5, sTRACE)
	}else{
		LOG("apiLost() - ${where}: Lost connection with API.", 1, sERROR)
		state.apiLostDump=getDebugDump()
		state.connected=sLOST
		updateMyLabel()
	}
	runIn(15, notifyApiLostHelper, [overwrite: true])
}

void notifyApiLostHelper(){
	if( (String)state.connected == sLOST ){
		LOG("Unscheduling Polling and refreshAuthToken. User MUST reintialize the connection with AutoConnect by running the AutoMower Manager App and logging in again", 0, sERROR)
		generateEventLocalParams() // Update the connection status
		// put a log for each child that we are lost
		if( debugLevel(3) ){
			def d=getChildDevices()
			d?.each{ oneChild ->
				LOG("apiLost() - notifying child: ${oneChild.device.displayName} of loss", 3, sERROR, null, oneChild)
			}
		}
		unschedule('pollScheduled')
		unschedule('scheduledWatchdog')
		runEvery3Hours('notifyApiLost')
	}
	notifyApiLost()
}

void notifyApiLost(){
	if( (String)state.connected == sLOST ){
		generateEventLocalParams()
		String notificationMessage="Your AutoMower Manager mower${((List<String>)settings.mowers)?.size()>1?'s are':' is'} disconnected AutoConnect. Please go to the AutoMower Manager and re-enter your AutoConnect account login credentials."
		sendMessage(notificationMessage)
		LOG("notifyApiLost() - API Connection Previously Lost. User MUST reintialize the connection with AutoConnect by running the AutoMower Manager App and logging in again", 0, sERROR)
	}else{
		// Must have restored connection
		unschedule("notifyApiLostHelper")
		unschedule("notifyApiLost")
	}
}

void runEvery6Minutes(handler){
	Integer randomSeconds=randomSeed.nextInt(49) + 10
	schedule("${randomSeconds} 0/6 * * * ?", handler)
}

void runEvery60Minutes(handler){
	runEvery1Hour(handler)
}

void updateMyLabel(){
	// Display connection status as part of the label...
	String myLabel
	myLabel=(String)state.appDisplayName
	if((myLabel == null) || !app.label.startsWith(myLabel)){
		myLabel=app.label
		if(!myLabel.contains('<span')) state.appDisplayName=myLabel
	}
	if(myLabel.contains('<span')){
		myLabel=myLabel.substring(0, myLabel.indexOf('<span'))
		state.appDisplayName=myLabel
	}
	String newLabel
	String key=(String)state.connected
	switch (key){
		case sFULL:
			newLabel=myLabel + "<span style=\"color:green\"> Online</span>"
			break
		case sWARN:
			newLabel=myLabel + "<span style=\"color:orange\"> Warning</span>"
			break
		case sLOST:
			newLabel=myLabel + "<span style=\"color:red\"> Offline</span>"
			break
		default:
			newLabel=myLabel
			break
	}
	if(newLabel && ((String)app.label != newLabel)) app.updateLabel(newLabel)
}

static String theMower()				{ return '<img src=https://raw.githubusercontent.com/imnotbob/autoMower/master/images/automower310d.jpg width=78 height=78 align=right></img>'}
static String getTheSectionMowerLogo()		{ return '<img src=https://raw.githubusercontent.com/imnotbob/autoMower/master/images/automower310d.jpg width=25 height=25 align=left></img>'}

static String pageTitle		(String txt)	{ return getFormat('header-ecobee','<h2>'+(txt.contains("\n") ? '<b>'+txt.replace("\n","</b>\n") : txt )+'</h2>') }
//static String pageTitleOld	(String txt)	{ return getFormat('header-ecobee','<h2>'+txt+'</h2>') }
static String sectionTitle	(String txt)	{ return getTheSectionMowerLogo() + getFormat('header-nobee','<h3><b>&nbsp;&nbsp;'+txt+'</b></h3>') }
static String smallerTitle	(String txt)	{ return txt ? '<h3 style="color:#5BBD76"><b><u>'+txt+'</u></b></h3>' : sBLANK}
//static String sampleTitle	(String txt)	{ return '<b><i>'+txt+'<i></b>' }
static String inputTitle	(String txt)	{ return '<b>'+txt+'</b>' }
//static String getWarningText()				{ return "<span style='color:red'><b>WARNING: </b></span>"}

static String getFormat(String type, String myText=sBLANK){
	switch(type){
		case "header-ecobee":
			return "<div style='color:#FFFFFF;background-color:#5BBD76;padding-left:0.5em;box-shadow: 0px 3px 3px 0px #b3b3b3'>${theMower()}${myText}</div>"
			break
		case "header-nobee":
			return "<div style='width:50%;min-width:400px;color:#FFFFFF;background-color:#5BBD76;padding-left:0.5em;padding-right:0.5em;box-shadow: 0px 3px 3px 0px #b3b3b3'>${myText}</div>"
			break
		case "line":
			return "<hr style='background-color:#5BBD76; height: 1px; border: 0;'></hr>"
			break
		case "title":
			return "<h2 style='color:#5BBD76;font-weight: bold'>${myText}</h2>"
			break
		case "warning":
			return "<span style='color:red'><b>WARNING: </b><i></span>${myText}</i>"
			break
		case "note":
			return "<b>NOTE: </b>${myText}"
			break
		default:
			return myText
			break
	}
}

static String getDtNow(){
	Date now=new Date()
	return formatDt(now)
}

static String formatDt(Date dt, Boolean tzChg=true){
	SimpleDateFormat tf=new SimpleDateFormat("E MMM dd HH:mm:ss z yyyy")
	if(tzChg) if(mTZ()){ tf.setTimeZone(mTZ()) }
	return (String)tf.format(dt)
}

@Field static final List<String> svdTSValsFLD=["lastCookieRrshDt"]
@Field volatile static Map<String,Map> tsDtMapFLD=[:]

private void updTsVal(String key, String dt=sNULL){
	String val=dt ?: getDtNow()
	if(key in svdTSValsFLD){ updServerItem(key, val); return }

	String appId=app.getId().toString()
	Map data=tsDtMapFLD[appId] ?: [:]
	if(key) data[key]=val
	tsDtMapFLD[appId]=data
	tsDtMapFLD=tsDtMapFLD
}

private void remTsVal(key){
	String appId=app.getId().toString()
	Map data=tsDtMapFLD[appId] ?: [:]
	if(key){
		if(key instanceof List){
			List<String> aa=(List<String>)key
			aa.each{ String k->
				if(data.containsKey(k)){ data.remove(k) }
				if(k in svdTSValsFLD){ remServerItem(k) }
			}
		}else{
			String sKey=(String)key
			if(data.containsKey(sKey)){ data.remove(sKey) }
			if(sKey in svdTSValsFLD){ remServerItem(sKey) }
		}
		tsDtMapFLD[appId]=data
		tsDtMapFLD=tsDtMapFLD
	}
}

private String getTsVal(String key){
	if(key in svdTSValsFLD){
		return (String)getServerItem(key)
	}
	String appId=app.getId().toString()
	Map tsMap=tsDtMapFLD[appId]
	if(key && tsMap && tsMap[key]){ return (String)tsMap[key] }
	return sNULL
}

Integer getLastTsValSecs(String val, Integer nullVal=1000000){
	return (val && getTsVal(val)) ? GetTimeDiffSeconds(getTsVal(val)).toInteger() : nullVal
}

@Field volatile static Map<String,Map> serverDataMapFLD=[:]

void updServerItem(String key, val){
	Map data
	data=atomicState?.serverDataMap
	data=data ?: [:]
	if(key){
		String appId=app.getId().toString()
		data[key]=val
		atomicState.serverDataMap=data
		serverDataMapFLD[appId]= [:]
		serverDataMapFLD=serverDataMapFLD
	}
}

void remServerItem(key){
	Map data
	data=atomicState?.serverDataMap
	data=data ?: [:]
	if(key){
		if(key instanceof List){
			List<String> aa=(List<String>)key
			aa?.each{ String k-> if(data.containsKey(k)){ data.remove(k) } }
		}else{ if(data.containsKey((String)key)){ data.remove((String)key) } }
		String appId=app.getId().toString()
		atomicState?.serverDataMap=data
		serverDataMapFLD[appId]= [:]
		serverDataMapFLD=serverDataMapFLD
	}
}

def getServerItem(String key){
	String appId=app.getId().toString()
	Map fdata
	fdata=serverDataMapFLD[appId]
	if(fdata == null) fdata=[:]
	if(key){
		if(fdata[key] == null){
			Map sMap=atomicState?.serverDataMap
			if(sMap && sMap[key]){
				fdata[key]=sMap[key]
			}
		}
		return fdata[key]
	}
	return null
}

Long GetTimeDiffSeconds(String lastDate, String sender=sNULL){
	try{
		if(lastDate?.contains("dtNow")){ return 10000 }
		Date lastDt=Date.parse("E MMM dd HH:mm:ss z yyyy", lastDate)
		Long start=lastDt.getTime()
		Long stop=wnow()
		Long diff= (Long)((stop - start) / 1000L)
		return diff.abs()
	} catch(ex) {
		String sndr= sender ? "$sender | " : sSPACE
		LOG("GetTimeDiffSeconds (${sndr}lastDate: ${lastDate})", 1, sERROR, ex)
		return 10000L
	}
}

@Field static final List<String> lLOGTYPES =	['error', 'debug', 'info', 'trace', 'warn']

@Field static final String sDEBUG		= 'debug'
@Field static final String sERROR		= 'error'
@Field static final String sINFO		= 'info'
@Field static final String sTRACE		= 'trace'
@Field static final String sWARN		= 'warn'

private Long wnow(){ return (Long)now() }
private static TimeZone mTZ(){ return TimeZone.getDefault() } // (TimeZone)location.timeZone
