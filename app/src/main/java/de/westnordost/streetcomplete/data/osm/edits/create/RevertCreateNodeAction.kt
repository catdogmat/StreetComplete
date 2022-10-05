package de.westnordost.streetcomplete.data.osm.edits.create

import de.westnordost.streetcomplete.data.osm.edits.ElementEditAction
import de.westnordost.streetcomplete.data.osm.edits.ElementIdProvider
import de.westnordost.streetcomplete.data.osm.edits.IsRevertAction
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.mapdata.MapDataChanges
import de.westnordost.streetcomplete.data.osm.mapdata.MapDataRepository
import de.westnordost.streetcomplete.data.osm.mapdata.Node
import de.westnordost.streetcomplete.data.osm.mapdata.Way
import de.westnordost.streetcomplete.data.upload.ConflictException
import kotlinx.serialization.Serializable
import java.lang.System.currentTimeMillis

/** Action reverts creation of a node */
@Serializable
data class RevertCreateNodeAction(
    val insertedIntoWayIds: List<Long> = emptyList()
) : ElementEditAction, IsRevertAction {

    override fun createUpdates(
        originalElement: Element,
        element: Element?,
        mapDataRepository: MapDataRepository,
        idProvider: ElementIdProvider
    ): MapDataChanges {
        val node = element as? Node ?: throw ConflictException("Element deleted")

        if (mapDataRepository.getRelationsForNode(node.id).isNotEmpty()) {
            throw ConflictException("Node is now member of a relation")
        }
        val waysById = mapDataRepository.getWaysForNode(node.id).associateBy { it.id }
        if (waysById.keys.any { it !in insertedIntoWayIds }) {
            throw ConflictException("Node is now also part of another way")
        }

        val editedWays = ArrayList<Way>(insertedIntoWayIds.size)
        for (wayId in insertedIntoWayIds) {
            // if the node is not part of the way it was initially in anymore, that's fine
            val way = waysById[wayId] ?: continue

            val nodeIds = way.nodeIds.filter { it != node.id }

            editedWays.add(way.copy(nodeIds = nodeIds, timestampEdited = currentTimeMillis()))
        }

        /* Independent of whether it makes sense or not to check for conflicts on reverting the
           creating (=deleting), it is not possible to check for conflicts between element and
           originalElement (tags changed, position changed) technically:

           On reverting, the "originalElement" from the edit that is being reverted is copied to
           this edit in ElementEditsController::undo. However, that "originalElement" of the
           "CreateNodeAction" is just an empty (dummy) element with no tags since that element did
           not exist yet.

           ElementEditsController would need to use the element as used currently in the app (from
           MapDataWithEditsSource) as basis for the "originalElement" but to not create a cyclic
           dependency, users of EditHistoryController would have to pass in the current element +
           geometry into ElementEditsController::undo.
           Instead, let's just not check for conflicts here.
         */
        return MapDataChanges(modifications = editedWays, deletions = listOf(node))
    }
}

// TODO id updates?!: way ids in CreateNodeAction.insertIntoWays and RevertCreateNodeAction.insertedIntoWayIds
/* need to be updated... when a way with e.g. id=-1 has been uploaded
 ..but they are somewhere in the action (persisted as json), not in the edit...

 in current architecture, what would need to be done is to fetch every element edit and ask its
 action to update the ids...

 possible solutions:

 - maintain a "dictionary" of negative ids to real ids; use that dictionary at a critical point to
   translate the old ids to the new ids - i.e. do not even change the ids in the database

 - make element edits dao into two tables, so that it is possible that several different element id+types
   refer to the same edit(?)
*/
