// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.metallic.chiaki.common.*
import com.metallic.chiaki.discovery.DiscoveryManager
import com.metallic.chiaki.discovery.serverMac
import com.metallic.chiaki.lib.DiscoveryHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainViewModel(val database: AppDatabase, val preferences: Preferences): ViewModel()
{
	val discoveryManager = DiscoveryManager().also {
		it.active = preferences.discoveryEnabled
		viewModelScope.launch {
			it.discoveryActive.collect { active ->
				preferences.discoveryEnabled = active
			}
		}
	}

	init
	{
		viewModelScope.launch(Dispatchers.IO) {
			combine(
				database.manualHostDao().getAll(),
				database.registeredHostDao().getAll(),
				discoveryManager.discoveredHosts
			) { manualHosts, registeredHosts, discoveredHosts -> Triple(manualHosts, registeredHosts, discoveredHosts) }
				.collect { (manualHosts, registeredHosts, discoveredHosts) ->
					rememberDiscoveredHosts(manualHosts, registeredHosts, discoveredHosts)
				}
		}
	}

	/**
	 * Discovery only works on the local network, so keep the address of every registered console seen there
	 * as a manual host, to still list it when connected from outside, e.g. over a VPN.
	 */
	private suspend fun rememberDiscoveredHosts(manualHosts: List<ManualHost>, registeredHosts: List<RegisteredHost>, discoveredHosts: List<DiscoveryHost>)
	{
		val macRegisteredHosts = registeredHosts.associateBy { it.serverMac }
		val remembered = manualHosts.mapNotNull { it.registeredHost }.toMutableSet()
		for(discoveredHost in discoveredHosts)
		{
			val registeredHost = discoveredHost.serverMac?.let { macRegisteredHosts[it] } ?: continue
			val addr = discoveredHost.hostAddr ?: continue
			if(!remembered.add(registeredHost.id))
				continue
			try {
				database.manualHostDao().insert(ManualHost(host = addr, registeredHost = registeredHost.id))
			} catch(_: Exception) {}
		}
	}

	val displayHosts by lazy {
		combine(
			database.manualHostDao().getAll(),
			database.registeredHostDao().getAll(),
			discoveryManager.discoveredHosts
		) { manualHosts, registeredHosts, discoveredHosts ->
			val macRegisteredHosts = registeredHosts.associateBy { it.serverMac }
			val idRegisteredHosts = registeredHosts.associateBy { it.id }
			val discoveredDisplayHosts = discoveredHosts.map {
				DiscoveredDisplayHost(it.serverMac?.let { mac -> macRegisteredHosts[mac] }, it)
			}
			// Don't list a console twice while it is both discovered and remembered
			val discoveredKeys: Set<Pair<Long?, String>> = discoveredDisplayHosts
				.mapNotNull { host -> host.registeredHost?.let { it.id to host.host } }
				.toSet()
			discoveredDisplayHosts +
			manualHosts
				.filter { it.registeredHost == null || (it.registeredHost to it.host) !in discoveredKeys }
				.map { ManualDisplayHost(it.registeredHost?.let { id -> idRegisteredHosts[id] }, it) }
		}.asLiveData()
	}

	val discoveryActive by lazy {
		discoveryManager.discoveryActive.asLiveData()
	}

	fun deleteManualHost(manualHost: ManualHost)
	{
		viewModelScope.launch(Dispatchers.IO) {
			try {
				database.manualHostDao().delete(manualHost)
			} catch(_: Exception) {}
		}
	}

	override fun onCleared()
	{
		super.onCleared()
		discoveryManager.dispose()
	}
}
