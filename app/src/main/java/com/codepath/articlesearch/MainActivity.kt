package com.codepath.articlesearch

import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.codepath.articlesearch.databinding.ActivityMainBinding
import com.codepath.asynchttpclient.AsyncHttpClient
import com.codepath.asynchttpclient.callback.JsonHttpResponseHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Headers
import org.json.JSONException

fun createJson() = Json {
    isLenient = true
    ignoreUnknownKeys = true
    useAlternativeNames = false
}

private const val TAG = "MainActivity/"
private const val SEARCH_API_KEY = BuildConfig.API_KEY
private const val ARTICLE_SEARCH_URL =
    "https://api.nytimes.com/svc/search/v2/articlesearch.json?api-key=${SEARCH_API_KEY}"

class MainActivity : AppCompatActivity() {
    private val articles = mutableListOf<DisplayArticle>()
    private lateinit var articlesRecyclerView: RecyclerView
    private lateinit var binding: ActivityMainBinding
    private lateinit var swipeContainer: SwipeRefreshLayout
    private lateinit var cacheSwitch: Switch
    private lateinit var clearCacheButton: Button
    private lateinit var connectionStatusText: TextView
    private lateinit var connectivityReceiver: ConnectivityReceiver
    private var isConnected = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        articlesRecyclerView = findViewById(R.id.articles)
        swipeContainer = findViewById(R.id.swipeRefreshLayout)
        cacheSwitch = findViewById(R.id.cacheSwitch)
        clearCacheButton = findViewById(R.id.clearCacheButton)
        connectionStatusText = findViewById(R.id.connectionStatusText)

        val articleAdapter = ArticleAdapter(this, articles)

        // Initialize and register connectivity receiver
        connectivityReceiver = ConnectivityReceiver { connected ->
            runOnUiThread {
                handleNetworkChange(connected, articleAdapter)
            }
        }
        registerReceiver(connectivityReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))

        val sharedPreferences = getSharedPreferences("userPreferences", MODE_PRIVATE)
        cacheSwitch.isChecked = sharedPreferences.getBoolean("CACHE_DATA", true)

        cacheSwitch.setOnCheckedChangeListener { _, isChecked ->
            with(sharedPreferences.edit()) {
                putBoolean("CACHE_DATA", isChecked)
                apply()
            }
        }

        clearCacheButton.setOnClickListener {
            clearCache(articleAdapter)
        }

        articlesRecyclerView.adapter = articleAdapter
        articlesRecyclerView.layoutManager = LinearLayoutManager(this).also {
            val dividerItemDecoration = DividerItemDecoration(this, it.orientation)
            articlesRecyclerView.addItemDecoration(dividerItemDecoration)
        }

        swipeContainer.setOnRefreshListener { // Set up swipe-to-refresh listener
            if (isConnected) {
                fetchArticles(articleAdapter)
            }
        }

        lifecycleScope.launch {
            (application as ArticleApplication).db.articleDao().getAll().collect { databaseList ->
                databaseList.map { entity ->
                    DisplayArticle(
                        entity.headline,
                        entity.articleAbstract,
                        entity.byline,
                        entity.mediaImageUrl
                    )
                }.also { mappedList ->
                    articles.clear()
                    articles.addAll(mappedList)
                    articleAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun handleNetworkChange(connected: Boolean, articleAdapter: ArticleAdapter) {
        isConnected = connected
        if (connected) {
            connectionStatusText.visibility = View.GONE
            // Automatically reload data when connection is restored
            fetchArticles(articleAdapter)
        } else {
            connectionStatusText.visibility = View.VISIBLE
            Toast.makeText(this, "You are offline", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(connectivityReceiver)
    }

    private fun clearCache(articleAdapter: ArticleAdapter) {
        lifecycleScope.launch(IO) {
            (application as ArticleApplication).db.articleDao().deleteAll()
            articles.clear()  // Clear the list in memory
            withContext(Dispatchers.Main) {
                // Notify adapter and update the UI on the main thread
                articleAdapter.notifyDataSetChanged()
                Log.i(TAG, "Cache cleared successfully")
            }
        }
    }

    private fun fetchArticles(articleAdapter: ArticleAdapter) {
        val client = AsyncHttpClient()
        client.get(ARTICLE_SEARCH_URL, object : JsonHttpResponseHandler() {
            override fun onFailure(
                statusCode: Int,
                headers: Headers?,
                response: String?,
                throwable: Throwable?
            ) {
                Log.e(TAG, "Failed to fetch articles: $statusCode")
                throwable?.let { Log.e(TAG, "Error: ${it.message}", it) }
                lifecycleScope.launch(Dispatchers.Main) {
                    swipeContainer.isRefreshing = false // Ensure this runs on the main thread
                }
            }

            override fun onSuccess(statusCode: Int, headers: Headers, json: JSON) {
                Log.i(TAG, "Successfully fetched articles: $json")
                try {
                    val parsedJson = createJson().decodeFromString(
                        SearchNewsResponse.serializer(),
                        json.jsonObject.toString()
                    )
                    parsedJson.response?.docs?.let { list ->
                        lifecycleScope.launch(IO) {
                            (application as ArticleApplication).db.articleDao().deleteAll()
                            (application as ArticleApplication).db.articleDao().insertAll(list.map {
                                ArticleEntity(
                                    headline = it.headline?.main,
                                    articleAbstract = it.abstract,
                                    byline = it.byline?.original,
                                    mediaImageUrl = it.mediaImageUrl
                                )
                            })
                            articles.clear()
                            articles.addAll(list.map {
                                DisplayArticle(
                                    it.headline?.main,
                                    it.abstract,
                                    it.byline?.original,
                                    it.mediaImageUrl
                                )
                            })
                            withContext(Dispatchers.Main) {
                                articleAdapter.notifyDataSetChanged()
                                swipeContainer.isRefreshing = false // Stop refreshing on the main thread
                            }
                        }
                    }
                } catch (e: JSONException) {
                    Log.e(TAG, "Exception: $e")
                    swipeContainer.isRefreshing = false // Stop refreshing animation on exception
                }
            }
        })
    }
}