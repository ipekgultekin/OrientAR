package com.example.orientar

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class ScoreboardActivity : AppCompatActivity() {

    private lateinit var rvScores: RecyclerView
    private lateinit var btnPlay: Button
    private lateinit var tvTotalStats: TextView

    // ✅ Bottom nav container’ları (XML’de LinearLayout id’leri)
    private lateinit var navHome: LinearLayout
    private lateinit var navUnit: LinearLayout
    private lateinit var navProfile: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scoreboard)

        rvScores = findViewById(R.id.rvScores)
        btnPlay = findViewById(R.id.btnPlay)
        tvTotalStats = findViewById(R.id.tvTotalStats)

        navHome = findViewById(R.id.navHome)
        navUnit = findViewById(R.id.navUnit)
        navProfile = findViewById(R.id.navProfile)

        rvScores.layoutManager = LinearLayoutManager(this)
        rvScores.adapter = ScoreboardAdapter(GameState.questions, GameState.bestTimes)

        refreshUI()

        btnPlay.setOnClickListener {
            // Direkt oyuna gir
            startActivity(Intent(this, TreasureHuntGameActivity::class.java))
            finish()
        }

        // Bottom nav clickleri
        navHome.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        navUnit.setOnClickListener {
            // TODO: OrientationUnitActivity varsa bağla
            // startActivity(Intent(this, OrientationUnitActivity::class.java))
        }

        navProfile.setOnClickListener {
            // TODO: ProfileActivity varsa bağla
            // startActivity(Intent(this, ProfileActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
        (rvScores.adapter as? ScoreboardAdapter)?.notifyDataSetChanged()
    }

    private fun refreshUI() {
        val solved = GameState.totalSolved
        val total = GameState.totalQuestions()
        val totalSeconds = GameState.totalTimeMs / 1000.0

        tvTotalStats.text = String.format(
            Locale.US,
            "SOLVED: %d / %d\nTOTAL TIME: %.1f sec",
            solved, total, totalSeconds
        )

        // ✅ İlk kez PLAY, sonra PLAY AGAIN
        btnPlay.text = if (solved == 0) "PLAY" else "PLAY AGAIN"
    }

    class ScoreboardAdapter(
        private val questions: List<Question>,
        private val bestTimes: Map<Int, Long>
    ) : RecyclerView.Adapter<ScoreboardAdapter.ScoreViewHolder>() {

        class ScoreViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imgTrophy: ImageView = itemView.findViewById(R.id.imgTrophy)
            val tvTitle: TextView = itemView.findViewById(R.id.tvQuestionTitle)
            val tvTime: TextView = itemView.findViewById(R.id.tvTime)
            val imgStatus: ImageView = itemView.findViewById(R.id.imgStatusIcon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScoreViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_score, parent, false)
            return ScoreViewHolder(view)
        }

        override fun onBindViewHolder(holder: ScoreViewHolder, position: Int) {
            val q = questions[position]
            val timeMs = bestTimes[q.id]
            val isSolved = timeMs != null

            holder.tvTitle.text = q.title

            if (isSolved) {
                val sec = timeMs!! / 1000.0
                holder.tvTime.text = String.format(Locale.US, "Completed in %.1f s", sec)

                holder.imgStatus.setImageResource(android.R.drawable.checkbox_on_background)
                holder.imgStatus.setColorFilter(Color.parseColor("#B71C1C"))

                // ✅ solved olunca küçük preview (assets’ten soru görseli)
                try {
                    holder.itemView.context.assets.open(q.answerImageAssetPath).use { ims ->
                        val drawable: Drawable? = Drawable.createFromStream(ims, null)
                        holder.imgTrophy.setImageDrawable(drawable)
                    }
                } catch (_: Exception) {
                    holder.imgTrophy.setImageResource(android.R.drawable.ic_menu_gallery)
                }

            } else {
                holder.tvTime.text = "Not solved yet"
                holder.imgStatus.setImageResource(android.R.drawable.checkbox_off_background)
                holder.imgStatus.setColorFilter(Color.GRAY)

                holder.imgTrophy.setImageResource(android.R.drawable.ic_lock_lock)
            }
        }

        override fun getItemCount(): Int = questions.size
    }
}
