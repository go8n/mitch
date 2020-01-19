package ua.gardenapple.itchupdater.database.game

import androidx.lifecycle.LiveData
import androidx.room.*
import ua.gardenapple.itchupdater.database.game.Game.Companion.GAME_ID
import ua.gardenapple.itchupdater.database.game.Game.Companion.TABLE_NAME
import ua.gardenapple.itchupdater.database.installation.Installation


@Dao
abstract class GameDao {
    @Query("SELECT * FROM $TABLE_NAME")
    abstract fun getAllGames(): LiveData<List<Game>>

    @Query("""SELECT * FROM $TABLE_NAME WHERE $GAME_ID IN
        (SELECT DISTINCT ${Installation.GAME_ID} FROM ${Installation.TABLE_NAME} WHERE ${Installation.IS_PENDING} = 0)""")
    abstract fun getAllInstalledGames(): LiveData<List<Game>>

    @Query("SELECT * FROM $TABLE_NAME WHERE $GAME_ID = :gameId LIMIT 1")
    abstract fun getGameById(gameId: Int): Game?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insert(vararg games: Game)

    @Update
    abstract fun update(vararg games: Game)

    @Transaction
    open fun upsert(vararg games: Game) {
        for(game in games) {
            val existingGame = getGameById(game.gameId)
            if (existingGame == null)
                insert(game)
            else {
                update(game)
            }
        }
    }
}