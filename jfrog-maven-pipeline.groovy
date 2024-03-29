http://jenkins.jfrogchina.com/view/IDCF/job/IDCF-docker-pipeline/

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def user_apikey

withCredentials([string(credentialsId: 'platform-key', variable: 'secret_text')]) {
    user_apikey = "${secret_text}"
}

node {
	def artiServer
	def warVersion
	def descriptor
	def buildInfo
	def rtMaven
	
	def requirements
    def jira_urls
    def revisionIds
    
    def JTtotal
    def JTpassed
    def passRate
    
    def sonar_Total

	stage('Prepare') {
		artiServer = Artifactory.server('arti-platform')
	}
	
	stage('SCM') {
		git branch: 'master', url: 'https://github.com/xingao0803/Guestbook-microservices-k8s.git'
	}

	stage('Set SNAPSHOT Version') {
	    warVersion = "${SNAPSHOT_Version_Pre}-SNAPSHOT"
	    descriptor = Artifactory.mavenDescriptor()
        descriptor.version = warVersion
    //  descriptor.setVersion "org.wangqing.guestbook-microservices-k8s:guestbook-service", "<new-version>"
        descriptor.failOnSnapshot = false
        descriptor.transform()
	}
	
	stage('SNAPSHOT Maven Build'){
	    buildInfo = Artifactory.newBuildInfo()
	   // buildInfo.number = "1.1.1"
	    buildInfo.name = 'libs-snapshot'

		rtMaven = Artifactory.newMavenBuild()
		//rtMaven.resolver server: artiServer, snapshotRepo: 'libs-snapshot', releaseRepo: 'libs-release'
        rtMaven.resolver server: artiServer, snapshotRepo: 'maven-virtual-test-inner', releaseRepo: 'libs-release'
		rtMaven.deployer server: artiServer, snapshotRepo: 'libs-snapshot-local', releaseRepo: 'libs-stage-local'
        rtMaven.deployer.deployArtifacts = false
        
		rtMaven.tool = 'maven'
        rtMaven.run pom: './pom.xml', goals: 'clean install', buildInfo: buildInfo
	}

	stage('Add JIRAResult to SNAPSHOT'){
	    def returnList = getRequirements();
	    
	    if (returnList.size() != 0) { 
            requirements = returnList[0];
            echo "requirements : ${requirements}"
            jira_urls = returnList[1];
            revisionIds = getRevisionIds();
            echo "revisionIds : ${revisionIds}"

            rtMaven.deployer.addProperty("project.issues", requirements)
            rtMaven.deployer.addProperty("project.issues.urls", jira_urls)
            rtMaven.deployer.addProperty("project.revisionIds", revisionIds)

	    }
	 
    }

    stage('Deploy SNAPSHOTS'){
        buildInfo.env.capture = true
        rtMaven.deployer.deployArtifacts buildInfo
        artiServer.publishBuildInfo buildInfo
    }
    
    stage('Set UniTest Results'){
        junit ( testResults: '**/surefire-reports/**/*.xml' )
    
        def testDatas=manager.build.getAction(hudson.tasks.junit.TestResultAction.class)
    
            if (testDatas) {
                result = testDatas.result
                JTtotal=result.getTotalCount()
                JTfailed=result.getFailCount()
                JTpassed=result.getPassCount()
                JTskiped=result.getSkipCount()
                passRate=result.getPassCount()/result.getTotalCount()*100
                passRate=passRate
           } else {
                JTtotal=0
                JTfailed=0
                JTpassed=0
                JTskiped=0
                passRate=0
            }
            
            print("Total unit test case Number : "+JTtotal)
            print("Pass unit test case Number : "+JTpassed)
            print("Passrate : "+ passRate)

      	    commandText = "curl  -H \"X-JFrog-Art-Api: ${user_apikey}\" -X PUT \""+artiServer.url+"/api/storage/libs-snapshot-local/org/wangqing/guestbook-microservices-k8s/guestbook-service/"+warVersion+"/?properties=" + 
    	              "unittest.summary.total_number="+JTtotal+";unittest.summary.pass_number="+JTpassed+";unittest.summary.pass_rate="+passRate+"\" ";
	        process = [ 'bash', '-c', commandText].execute().text
    }

    
    stage('Generate Release Version'){
        if( passRate > 95 ) {
            warVersion = "${Release_Version_Pre}.${BUILD_NUMBER}"
            descriptor = Artifactory.mavenDescriptor()
            descriptor.version = warVersion
            descriptor.failOnSnapshot = true
            descriptor.transform()
        }else{
            echo "Can not pass quality_gate with passRate ${passRate}%"
            exit()
        }
    }
    
	stage('Release Maven Build'){
	    buildInfo = Artifactory.newBuildInfo()
	    buildInfo.name = 'libs-release'
		rtMaven = Artifactory.newMavenBuild()

		rtMaven.resolver server: artiServer, snapshotRepo: 'libs-snapshot', releaseRepo: 'libs-release'
		rtMaven.deployer server: artiServer, snapshotRepo: 'libs-snapshot-local', releaseRepo: 'libs-stage-local'
        rtMaven.deployer.deployArtifacts = false
        
		rtMaven.tool = 'maven'
        rtMaven.run pom: './pom.xml', goals: 'clean install -Dmaven.test.skip=true', buildInfo: buildInfo
        
    /*    def config = """{
                    "version": 1,
                    "issues": {
                            "trackerName": "JIRA",
                            "regexp": "#([\\w\\-_\\d]+)\\s(.+)",
                            "keyGroupIndex": 1,
                            "summaryGroupIndex": 2,
                            "trackerUrl": "http://jira.jfrogchina.com:8081/browse/",
                            "aggregate": "true",
                            "aggregationStatus": "Released"
                    }
                }"""
        buildInfo.issues.collect(artiServer, config)
    */
        if (requirements) {
            rtMaven.deployer.addProperty("project.issues", requirements)
            rtMaven.deployer.addProperty("project.issues.urls", jira_urls)
            rtMaven.deployer.addProperty("project.revisionIds", revisionIds)
        }
        
        buildInfo.env.capture = true
        rtMaven.deployer.deployArtifacts buildInfo
		artiServer.publishBuildInfo buildInfo
		
      	commandText = "curl  -H \"X-JFrog-Art-Api: ${user_apikey}\" -X PUT \""+artiServer.url+"/api/storage/libs-stage-local/org/wangqing/guestbook-microservices-k8s/guestbook-service/"+warVersion+"/?properties=" + 
    	              "unittest.summary.total_number="+JTtotal+";unittest.summary.pass_number="+JTpassed+";unittest.summary.pass_rate="+passRate+"\" ";
	    process = [ 'bash', '-c', commandText].execute().text

	}
	
	stage('Sonar Test ') {
		// Sonar scan
            def scannerHome = tool 'sonarClient';
            // withSonarQubeEnv('sonar') {
            //     sh "${scannerHome}/bin/sonar-runner -Dsonar.projectKey=${JOB_BASE_NAME} -Dsonar.sources=./guestbook-service/src/main -Dsonar.tests=./guestbook-service/src/test"
            // }
	}
	//添加sonar扫描结果到SNAPSHOT包上
// 	stage("Add SonarResult to SNAPSHOT"){
// 		    //获取sonar扫描结果
// 		    def getSonarIssuesCmd = "curl  GET -v http://47.93.114.82:9000/api/issues/search?componentRoots=${JOB_BASE_NAME}";
// 		    echo "getSonarIssuesCmd:"+getSonarIssuesCmd
// 		    process = [ 'bash', '-c', getSonarIssuesCmd].execute().text

// 		    //增加sonar扫描结果到artifactory
// 		    def jsonSlurper = new JsonSlurper()
// 		    def issueMap = jsonSlurper.parseText(process);
// 		    echo "issueMap:"+issueMap
// 		    echo "Total:"+issueMap.total
// 		    sonar_Total =  issueMap.total
		    
// 		    commandSonar = "curl -H \"X-JFrog-Art-Api: ${user_apikey}\" -X PUT \""+artiServer.url+"/api/storage/libs-stage-local/org/wangqing/guestbook-microservices-k8s/guestbook-service/"+warVersion+"/?properties=" + 
// 		                    "quality.gate.sonarUrl=http://47.93.114.82:9000/dashboard/index/${JOB_BASE_NAME};quality.gate.sonarIssue="+sonar_Total+"\" ";
// 		    echo commandSonar
// 		    process = [ 'bash', '-c', commandSonar].execute().text
// 	}
	
	stage('Test Approval'){
	    if ( sonar_Total < 10 ) {
    	    commandText = "curl  -H \"X-JFrog-Art-Api: ${user_apikey}\" -X PUT \""+artiServer.url+"/api/storage/libs-stage-local/org/wangqing/guestbook-microservices-k8s/guestbook-service/"+warVersion+"/?properties=test.approve=true\" ";
	        process = [ 'bash', '-c', commandText].execute().text
	    }
	} 
	
	stage('promotion'){
		def promotionConfig = [
			'buildName'   : buildInfo.name,
			'buildNumber' : buildInfo.number,
			'targetRepo'  : 'libs-release-local',
			'comment': 'this is the promotion comment',
			'sourceRepo':'libs-stage-local',
			'status': 'Released',
			'includeDependencies': false,
			'failFast': true,
			'copy': true
		]
		artiServer.promote promotionConfig
	}

}

@NonCPS
def getRequirements(){
    def reqIds = "";
    def urls = "";
    def jira_url = "http://jira.jfrogchina.com:8081/browse/";
    
    final changeSets = currentBuild.changeSets
    echo 'changeset count:'+ changeSets.size().toString()
    if ( changeSets.size() == 0 ) {
        return reqIds;
    }
    final changeSetIterator = changeSets.iterator()
    while (changeSetIterator.hasNext()) {
        final changeSet = changeSetIterator.next();
        def logEntryIterator = changeSet.iterator();
        while (logEntryIterator.hasNext()) {
            final logEntry = logEntryIterator.next()
            def patten = ~/#[\w\-_\d]+/;
            def matcher = (logEntry.getMsg() =~ patten);
            def count = matcher.getCount();
            for (int i = 0; i < count; i++){
                reqIds += matcher[i].replace('#', '') + ","
                urls += jira_url + matcher[i].replace('#', '') + ","
            }
        }
    }
    
    if (reqIds) {
        def returnList = [ reqIds[0..-2], urls[0..-2] ]
        return returnList;
    }else
    {
        return reqIds
    }
}

@NonCPS
 def getRevisionIds(){
    def reqIds = "";
    final changeSets = currentBuild.changeSets
    if ( changeSets.size() == 0 ) {
        return reqIds;
    }
    final changeSetIterator = changeSets.iterator()
    while (changeSetIterator.hasNext()) {
        final changeSet = changeSetIterator.next();
        def logEntryIterator = changeSet.iterator();
        while (logEntryIterator.hasNext()) {
            final logEntry = logEntryIterator.next()
            reqIds += logEntry.getRevision() + ","
        }
    }
    return reqIds[0..-2]
}
