package com.tvmonitor.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvmonitor.app.R
import com.tvmonitor.app.data.Listing

class ListingAdapter(
    private val onClick: (Listing) -> Unit
) : ListAdapter<Listing, ListingAdapter.ViewHolder>(Diff()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.listingImage)
        val title: TextView = view.findViewById(R.id.listingTitle)
        val price: TextView = view.findViewById(R.id.listingPrice)
        val location: TextView = view.findViewById(R.id.listingLocation)
        val time: TextView = view.findViewById(R.id.listingTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_listing, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val listing = getItem(position)
        holder.title.text = listing.title
        holder.price.text = listing.price
        holder.location.text = listing.location
        holder.time.text = age(listing)

        if (listing.imageUrl.isNotBlank()) {
            Glide.with(holder.image.context)
                .load(listing.imageUrl)
                .placeholder(R.drawable.ic_tv)
                .centerCrop()
                .into(holder.image)
        } else {
            holder.image.setImageResource(R.drawable.ic_tv)
        }

        holder.itemView.setOnClickListener { onClick(listing) }
    }

    /**
     * How long ago the listing was posted - not how long ago this phone saw it.
     *
     * The card used to show time since discovery, so a listing that had been up
     * for fifty minutes read "Just now" the moment it was found. For a trader
     * whose stock sells in about ten minutes that is worse than showing nothing:
     * it makes stale listings look like the freshest thing on the screen.
     *
     * ageMinutes is fixed at the moment of the scan, so the time that has passed
     * since is added back on to keep the figure current while the app is open.
     */
    private fun age(listing: Listing): String {
        val posted = listing.ageMinutes
            ?: return "age unknown - found ${elapsed(listing.discoveredAt)}"
        val sinceFound = (System.currentTimeMillis() - listing.discoveredAt) / 60_000
        val total = posted + sinceFound
        return when {
            total < 1 -> "Listed just now"
            total < 60 -> "Listed ${total}m ago"
            total < 1440 -> "Listed ${total / 60}h ago"
            else -> "Listed ${total / 1440}d ago"
        }
    }

    private fun elapsed(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            else -> "${diff / 86_400_000}d ago"
        }
    }

    class Diff : DiffUtil.ItemCallback<Listing>() {
        override fun areItemsTheSame(a: Listing, b: Listing) = a.id == b.id
        override fun areContentsTheSame(a: Listing, b: Listing) = a == b
    }
}
