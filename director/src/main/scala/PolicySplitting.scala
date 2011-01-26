package edu.berkeley.cs.scads.director

import edu.berkeley.cs.scads.comm.{PartitionService,StorageService}
import scala.collection.mutable.ListBuffer
import net.lag.logging.Logger

class BestFitPolicySplitting(
	val performanceModel:PerformanceModel,
	val getSLA:Double,
	val putSLA:Double,
	val slaQuantile:Double,
	val blocking:Boolean,
	val machineInterval:Long,
	val serverRemoveTime:Long,
	override val workloadPredictor:WorkloadPrediction,
	val doReplication:Boolean,
	val doServerAllocation:Boolean,
	val nHotStandbys:Int,
	val reads:Int,
	val splitQueue:java.util.concurrent.LinkedBlockingQueue[(Option[org.apache.avro.generic.GenericRecord],Seq[Option[org.apache.avro.generic.GenericRecord]])]
) extends Policy(workloadPredictor) {
  
  val workloadThreshold = System.getProperty("workloadThreshold","1000").toInt
	val performanceEstimator = ConstantThresholdPerformanceEstimator(workloadThreshold,getSLA, putSLA, slaQuantile, reads)
  val splitFactor = 10
  val MIN_R = 1
  val MAX_R = 10
  
  //// state of policy during a single pass
	//var currentConfig:ClusterState = null
	//var runningActions:List[Action] = null
	var actions:ListBuffer[Action] = null
	var ghostActions:ListBuffer[Action] = null
	var receivers:scala.collection.mutable.Set[StorageService] = null
	var partReplicas:scala.collection.mutable.Set[Option[org.apache.avro.generic.GenericRecord]] = null
	var ghosts:scala.collection.mutable.Set[StorageService] = null
	//var workload:WorkloadHistogram = null
  
  override def periodicUpdate(state:ClusterState) = {
    // apply recently made split or merge changes to partitions
    if (workloadPredictor.getCurrentSmoothed != null) {
      logger.debug("current smoothed workload:\n%s",workloadPredictor.getCurrentSmoothed.toString)
      var partitionStats = workloadPredictor.getCurrentSmoothed.rangeStats
      while (splitQueue.peek != null) {
        val (split, newParts) = splitQueue.poll
        val newPartWorkload = partitionStats(split) * (1.0/(newParts.size+1))
        logger.debug("creating %d-way split in old smoothed partition %s", newParts.size+1, split.toString)
        (newParts ++ List(split)).foreach(p => partitionStats = partitionStats(p) = newPartWorkload)
      }
      workloadPredictor.addHistogram( state.workloadRaw, WorkloadHistogram(partitionStats), state.time )
      logger.debug("prediction has %d keys", workloadPredictor.getCurrentSmoothed.rangeStats.keys.size)
    } // end if
    else workloadPredictor.addHistogram( state.workloadRaw, state.time )
  }
  
  def act(config:ClusterState, actionExecutor:ActionExecutor) = {
    logger.info("Running BestFitPolicySplitting")
    
    // do blocking execution
		if (actionExecutor.allMovementActionsCompleted) {
		  var prediction = workloadPredictor.getPrediction
		  logger.debug("prediction has %d keys, config has %d", prediction.rangeStats.keys.size, config.workloadRaw.rangeStats.keys.size)
      if (!prediction.rangeStats.keys.sameElements(config.workloadRaw.rangeStats.keys)) prediction = config.workloadRaw
      else logger.info("smoothed workload:\n%s",prediction.toString)
			logger.info(config.serverWorkloadString)
			runPolicy( config, /*workloadPredictor.getPrediction*/prediction, actionExecutor.getUncompleteServerActions )
			logger.debug("# actions for executor: %d",actions.size)
			for (action <- actions) {
				//action.computeNBytesToCopy(state)
				actionExecutor.addAction(action)
			}
			if (!actions.isEmpty) lastDecisionTime = System.currentTimeMillis
			lastIterationState = "act"
		} else {
			lastIterationState = "blocked"
			logger.info("blocked on action completion: %s", actions.mkString(","))
		}
  }
  
  def applyRunningActions(serverActions:List[Action], config:ClusterState):ClusterState = {
		logger.debug("  number of actions to replay: %d",serverActions.size)
    var currentConfig = config
    
		for (action <- serverActions) {
			logger.debug("  applying "+action)
			currentConfig = action.preview(currentConfig)

			// update ghost servers
			action match {
				case a:AddServer => ghosts += a.fakeServer
				case a:RemoveServers => ghosts :: a.servers
				case _ =>
			}
		}
		logger.debug("config after replaying running actions:\n%s",currentConfig.serverWorkloadString)
		currentConfig
	}
  
  def runPolicy(_config:ClusterState, _workload:WorkloadHistogram, _runningActions:List[Action]) {
		logger.debug("starting runPolicy")
		//startingConfig = _config.clone
		//currentConfig = _config.clone
		//workload = _workload
		
		// initialize state of policy
		//runningActions = _runningActions
		actions = new ListBuffer[Action]()
		ghostActions = new ListBuffer[Action]()
		receivers = scala.collection.mutable.Set[StorageService]()
		partReplicas = scala.collection.mutable.Set[Option[org.apache.avro.generic.GenericRecord]]()
		ghosts = scala.collection.mutable.Set[StorageService]()
		//serverReplicas = scala.collection.mutable.Set[StorageService]()
		//groupActions = new scala.collection.mutable.HashMap[(StorageService,StorageService,String),ListBuffer[Action]]()
		//groupGhostActions = new scala.collection.mutable.HashMap[(StorageService,StorageService,String),ListBuffer[Action]]()
		//actionOrdering = new scala.collection.mutable.HashMap[(String,String),Long]()
		
		// apply running actions to config?
		var config = applyRunningActions(_runningActions, _config)
		
		// STEP 1: handle overloaded servers --------------------------
		val violationsPerServer = performanceEstimator.perServerViolations(config,_workload,getSLA,putSLA,slaQuantile)
 		var workloadPerServer = PerformanceEstimator.estimateServerWorkloadReads(config,_workload,reads)
 		
 		// list of overloaded servers (sorted by workload, high to low)
		val overloadedServers = violationsPerServer.filter( p => p._2 ).map( p => p._1 ).toList.sortWith( workloadPerServer(_)>workloadPerServer(_) )
    config = handleOverloaded(config, _workload, overloadedServers)
    
    // STEP 2: try to reduce replication and coalesce existing servers, and merging partitions
    config = handleReplicaReduction(config,_workload)
    
		// order merge candidates by increasing workload
		workloadPerServer = PerformanceEstimator.estimateServerWorkloadReads(config,_workload,reads)
		var mergeCandidates = (config.servers -- overloadedServers -- receivers -- ghosts).
									toList.sort( workloadPerServer(_)<workloadPerServer(_) ) // TODO: is 'receivers' too broad a set to exclude?
		config = handleUnderloaded(config,_workload, mergeCandidates)
		
	// STEP 3: add/remove servers as necessary
	}

  
  /**
  * foreach partition Ps on overloaded server S, find the set of servers Smin that could fit Ps
  * case 1: there is no Smin that Ps can fit on
  *   case a: split(Ps) is possible. split Ps with a factor f, and allocate a new server
  *   case b: cannot split(Ps) (due to constraint on partition min size or too many partitions/server)
  *     case i: load(Ps) spread across additional servers in Smin will solve issue. replicate(Ps) on those Smin
  *     case ii: replicating on existing servers won't solve problem. allocate a new server
  * case 2: there is a set of Smins (or we could launch a new server?)
  *   case a: there exists an Smin with potential for merging partitions, move Ps to that Smin
      case b: no merging potential exists, just move Ps to the ____ Smin
  */
  def handleOverloaded(state:ClusterState, workload:WorkloadHistogram, servers:Seq[StorageService]):ClusterState = {
    var currentState = state // will be updated as actions are applied to fix each server
    logger.debug("overloaded servers: %s", servers.mkString(","))
    
    servers.foreach(server => { // TODO: are these sorted by workload?
      currentState = fixOverloadedServer(state,workload,server)
      
    }) // end loop on overloaded servers
    currentState
  }
  
  private def fixOverloadedServer(state:ClusterState, workload:WorkloadHistogram, server:StorageService):ClusterState = {
    var currentState = state
    
    val partitions = currentState.partitionsOnServers(List(server)).toList.sortWith( workload.rangeStats(_).sum > workload.rangeStats(_).sum )
    val numPartitions = partitions.size
    partitions.foreach(partition=> {
      // TODO: think about which actions can touch the same server. e.g. can a split server be split again (no!)
      
      // get order of potential targets for move
			val workloadPerServer = PerformanceEstimator.estimateServerWorkloadReads(currentState,workload,reads)
			val violationsPerServer = performanceEstimator.perServerViolations(currentState,workload,getSLA,putSLA,slaQuantile)
			var orderedPotentialTargets = violationsPerServer.filter( p => !p._2 /*&& !serverReplicas.contains(p._1)*/ ).map( p => p._1 ).toList.sortWith( workloadPerServer(_)>workloadPerServer(_) )
			logger.debug("  orderedPotentialTargets = "+orderedPotentialTargets.mkString(","))
			if (numPartitions >= splitFactor) { // allow use of empty servers if are over min partitons per server // TODO: when to use an empty server?
			  orderedPotentialTargets ++ currentState.getEmptyServers
        logger.debug("  orderedPotentialTargets (including empty) = "+orderedPotentialTargets.mkString(","))
      }
    
      // remove from potential targets servers that already have this range, and don't have too much data, or ghost servers
			val serversWithRange = currentState.serversForKey(partition)
			val serverKeyCount = 0 // TODO: use partition counts
			orderedPotentialTargets = orderedPotentialTargets.filter(s => !serversWithRange.contains(s)/* && !ghosts.contains(s)*//* && serverKeyCount(s) < maxKeysPerServer*/)
			logger.debug("  orderedPotentialTargets (filtered)= {%s}",orderedPotentialTargets.mkString(","))

      // try moving the partitions to one of the targets  
      // as iterate through potential servers, check if one is mergeable as well
      val partService = currentState.partitionOnServer(partition,server)
      val stateAfterMove = findMoveAction(server,partService, orderedPotentialTargets, currentState, workload)
      if (stateAfterMove.isDefined) currentState = stateAfterMove.get
      else { // there's no server that can take partition, try splitting or replicating
        val splitActions = trySplitting(partition,currentState)
        if (splitActions.isDefined) { // do split
          
          splitActions.get.foreach(a=> currentState = addAction(a,currentState))
        }
        else { // try replicating
          val serversWithPartition = currentState.serversForKey( partition )
          val nReplicas = serversWithPartition.size
      		logger.debug("  before further replication, have %d replicas for %s",nReplicas,partition.toString)
      		
      		// try increasing replication using the potentialTargets gotten above
      		val repActions = tryReplicating(partService,server,nReplicas,orderedPotentialTargets , currentState,workload)
      		if (repActions.isDefined) { repActions.get.foreach(a => currentState = addAction(a, currentState)); partReplicas += partition }
        }
      }
      if (!performanceEstimator.violationOnServer(currentState,workload,server)) return currentState
    }) // end loop on partitions on a server
    
    currentState
  }
  
  def handleReplicaReduction(state:ClusterState, workload:WorkloadHistogram):ClusterState = {
    var currentState = state
    
    // try reducing replication 
    val binsToReduce = currentState.partitionsWithMoreThanKReplicas(MIN_R) -- partReplicas // TODO: (is that really necessary tho?, since should fail below predicate)
		if (!binsToReduce.isEmpty){
			logger.debug("** reducing replication")
			logger.debug("bins to reduce: "+binsToReduce.mkString(", "))
		}
		
		for (range <- binsToReduce ) {
			logger.debug("  reducing replication of "+range)
			var done = false
			var serversWithRange = currentState.serversForKey( range )
			
			while (serversWithRange.size > MIN_R && !done) {
        val server = serversWithRange.toList.first
        val action = DeletePartition(currentState.partitionOnServer(range,server), server, REP_REDUCE)
        val tmpConfig = action.preview(currentState)
        if (!performanceEstimator.violationOnServers(tmpConfig, workload, serversWithRange) ) {
          currentState = addAction( action, currentState )
        //  serverReplicas -= server
          serversWithRange = currentState.serversForKey( range ) // get updated list of servers with this range
        } 
        else done = true // can't remove any more replicas without violating SLOs
			}
		}
		currentState
  }
  
  /**
  * have underloaded servers in sorted order from min to max loaded
  * foreach partition Ps on underloaded server S
  * x (not done here) case 1: replication factor of Ps is > minReplication and it's safe to remove a replica. delete Ps
  * case 2: another underloaded server can take Ps. move Ps there. (preferable the most loaded underloaded server)
  */
  def handleUnderloaded(state:ClusterState, workload:WorkloadHistogram, servers:Seq[StorageService]):ClusterState = {
    var currentState = state
    logger.debug("underloaded servers: %s", servers.mkString(","))
    var workloadPerServer = PerformanceEstimator.estimateServerWorkloadReads(currentState,workload,reads)
    
    var orderedPotentialTargets = 
			currentState.servers.
				filter( s => currentState.serversToPartitions(s).size>0 && !ghosts.contains(s)/* && !serverReplicas.contains(s)*/).
				toList.
				sort( workloadPerServer(_)>workloadPerServer(_) )
				
  	var minRangeCouldntMove:Option[org.apache.avro.generic.GenericRecord] = null // track the range with smallest workload that we couldn't move, avoid trying to move something larger
		for (server <- servers) {
		  if (!receivers.contains(server)) {
		    orderedPotentialTargets = orderedPotentialTargets.filter( workloadPerServer(_)>=workloadPerServer(server) )
  		  val partitions = currentState.partitionsOnServers(List(server))
  			var successfulMove = false
  			for (partition <- partitions) {
  				if (minRangeCouldntMove == null || workload.rangeStats(partition).compare(workload.rangeStats(minRangeCouldntMove)) < 0) {
  					logger.debug("  trying to move range "+partition+": "+workload.rangeStats(partition))
  					val stateAfterMove = findMoveAction(server, currentState.partitionOnServer(partition,server), orderedPotentialTargets  - server, state, workload)//tryMoving(range,server,orderedPotentialTargets)
  					if (stateAfterMove.isDefined) { currentState = stateAfterMove.get; successfulMove = true }
  					if (!successfulMove) minRangeCouldntMove = partition
  				}
  			} // end for on partitions
		  }
		} // end for on servers
    
    currentState
  }
  
  /**
  * for each pair of partitions PsPe
  * case 1: PsPe are mergable and all servers involved are not overloaded. merge.
  */
  def handleMerging() = {}
  
  private def findMoveAction(server:StorageService, partService:PartitionService, orderedPotentialTargets:Seq[StorageService], state:ClusterState, workload:WorkloadHistogram):Option[ClusterState] = {
    var currentState = state
    var movableActions:Option[List[Action]] = None
    var targetServer:Option[StorageService] = None
    for (target <- orderedPotentialTargets) {
      if (!movableActions.isDefined) { movableActions = tryMoving(partService,server,target,currentState,workload); if (movableActions.isDefined) targetServer=Some(target) }
      //TODO if (!mergableActions.isDefined) mergableActions = tryMerging(partService,server,target,currentState,workload)
    }
  
    if (targetServer.isDefined) { // successfully have a target server for the partition
      receivers += targetServer.get
      if (!ghosts.contains(targetServer.get)) movableActions.get.foreach(a=> currentState = addAction(a,currentState))
      else movableActions.get.foreach(a=> currentState = addGhostAction(a,currentState))
      Some(currentState)
    }
    else None
  }
  
  private def tryMoving(part:PartitionService,sourceServer:StorageService, target:StorageService, config:ClusterState, workload:WorkloadHistogram):Option[List[Action]] = {
    // try moving 'range' to 'target' and create new config
		val replicateAction = ReplicatePartition(part,sourceServer,target,MOVE_OVERLOAD)
		val deleteAction = DeletePartition(part,sourceServer,MOVE_OVERLOAD)
		var tmpConfig = replicateAction.preview(config)
		tmpConfig = deleteAction.preview(tmpConfig)
		
		// if no violation on 'target' after the move, it's safe, so return the actions
		if (!performanceEstimator.violationOnServer(tmpConfig,workload,target)) Some(List(replicateAction,deleteAction))
		else None
  }
  private def tryMerging(part:PartitionService,sourceServer:StorageService, target:StorageService, config:ClusterState, workload:WorkloadHistogram):(Boolean,List[Action]) = (false,List[Action]())
  
  private def trySplitting(part:Option[org.apache.avro.generic.GenericRecord], config:ClusterState):Option[List[Action]] = {
    var currentConfig = config
    // determine how many total partitions are on the servers that have this partition
    val serversWithPart = currentConfig.serversForKey(part)
    var maxParts = 0
    serversWithPart.foreach(s => { val numParts = currentConfig.serversToPartitions(s).size; if (numParts > maxParts) maxParts = numParts })
    
    val canSplit = if (maxParts >= splitFactor) { logger.warning("Can't split since hit max split"); false} else true // TODO: check partition size as well
    
    if (canSplit) {// schedule the split action and an addserver if needed
      val splitAction = SplitPartition(part,splitFactor,MOVE_OVERLOAD)
      val (emptyServer, addServerAction) = getEmptyServer(MOVE_OVERLOAD,currentConfig)
      if (addServerAction.isDefined) { ghosts += emptyServer; currentConfig = addGhostAction(addServerAction.get,currentConfig); Some(List(splitAction,addServerAction.get)) }
      else Some(List(splitAction))
      //Some(List(, AddServer(ClusterState.getRandomServerNames(config,1).head,MOVE_OVERLOAD)))
    }
    else None
    // TODO: why again are we adding a server here? can't we just use an empty one?
  }
  
  private def tryReplicating(part:PartitionService,sourceServer:StorageService, startingReplicas:Int, orderedPotentialTargets:Seq[StorageService], config:ClusterState, workload:WorkloadHistogram):Option[List[Action]] = {
    var done = false
    var currentConfig = config
    var nReplicas = startingReplicas
    var repActions = new scala.collection.mutable.ListBuffer[Action]()
    
    for (target <- orderedPotentialTargets) {
      if (!done && nReplicas < MAX_R) {
        val replicateAction = ReplicatePartition(part,sourceServer,target,MOVE_OVERLOAD)
    		var tmpConfig = replicateAction.preview(currentConfig)
        if (!performanceEstimator.violationOnServer(tmpConfig,workload,target)) { // ok for target to move there	
      		receivers += target
      		nReplicas += 1
      		if (ghosts.contains(target)) {
      		  currentConfig = addGhostAction(replicateAction,currentConfig)
      		}
      		else {
      		  currentConfig = tmpConfig
      		  repActions += replicateAction
      		}
      		if (!performanceEstimator.violationOnServer(currentConfig,workload,sourceServer)) done = true
        }        
      }
    }
    // if don't have enough replicas still, start adding more servers
    while (!done && nReplicas < MAX_R) {
      val (emptyServer, addServerAction) = getEmptyServer(REP_ADD,currentConfig)
      if (addServerAction.isDefined) { ghosts += emptyServer; currentConfig = addGhostAction(addServerAction.get,currentConfig); repActions += addServerAction.get }
      val replicateAction = ReplicatePartition(part,sourceServer,emptyServer,REP_ADD)
      currentConfig = replicateAction.preview(currentConfig)
      repActions += replicateAction
      if (!performanceEstimator.violationOnServer(currentConfig,workload,sourceServer)) done = true
    }
    if (!done) logger.warning("didn't have enough servers to create replicas")

    if (!repActions.isEmpty) Some(repActions.toList) else None
  }
  
  private def addAction(action:Action, config:ClusterState):ClusterState = {
		logger.info("ADD ACTION: %s",action)
		actions += action
		action.preview(config)
	}
	private def addGhostAction(action:Action, config:ClusterState):ClusterState = {
		logger.debug("ADD GHOST ACTION: "+action)
		ghostActions += action
		action.preview(config)
	}
  private def getEmptyServer(note:String, config:ClusterState):(StorageService, Option[Action]) = {
		var found = false
		var emptyServer:StorageService = null

		// try to find an empty server among the current servers
		for (server <- config.servers; if emptyServer==null)
			if (config.serversToPartitions(server).size==0)
				emptyServer = server

		if (emptyServer!=null) {
		  logger.debug("getting empty existing server %s",emptyServer.toString)
			(emptyServer, None)
		}
		else {
			// don't have an empty server, so add one
			val addServerAction = AddServer(ClusterState.getRandomServerNames(config,1).head,note)
			logger.debug("getting new empty server %s",addServerAction.fakeServer.toString)
			(addServerAction.fakeServer,Some(addServerAction))
		}
	}
  
} // end class