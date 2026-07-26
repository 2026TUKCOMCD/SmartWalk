package com.smartwalker.presentation.navigation

import android.graphics.Color
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.route.RouteLine
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles
import com.kakao.vectormap.route.RouteLineStylesSet
import com.smartwalker.R
import com.smartwalker.domain.model.FusedPosition
import com.smartwalker.domain.model.Route

/**
 * 안내 화면에 표시하는 미니 지도 — 현재 위치 마커 + 경로 폴리라인만 그린다.
 *
 * 시각장애인 사용자에게는 의미 없는 시각 정보(음성 안내는 [NavigationGuidanceService]가 별도 처리)이므로
 * 호출부(NavigationScreen)에서 TalkBack 접근성 트리에서 제외해야 한다.
 */
@Composable
fun KakaoMapView(
    currentPosition: FusedPosition?,
    route: Route?,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var kakaoMap by remember { mutableStateOf<KakaoMap?>(null) }
    var currentLocationLabel by remember { mutableStateOf<Label?>(null) }
    var currentRouteLine by remember { mutableStateOf<RouteLine?>(null) }
    var drawnRoute by remember { mutableStateOf<Route?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                start(
                    object : MapLifeCycleCallback() {
                        override fun onMapDestroy() {
                            kakaoMap = null
                        }
                        override fun onMapError(error: Exception) {
                            Log.e(TAG, "지도 로드 실패 (앱 키/플랫폼 등록 확인 필요)", error)
                        }
                    },
                    object : KakaoMapReadyCallback() {
                        override fun onMapReady(map: KakaoMap) {
                            kakaoMap = map
                        }
                    }
                )
                mapView = this
            }
        }
    )

    // Compose 화면 생명주기 ↔ MapView 생명주기 동기화. resume()/pause() 호출을 누락하면
    // Kakao Maps SDK 문서상 알 수 없는 크래시가 발생할 수 있다고 명시되어 있다.
    DisposableEffect(lifecycleOwner, mapView) {
        val view = mapView
        if (view == null) {
            onDispose {}
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> view.resume()
                    Lifecycle.Event.ON_PAUSE -> view.pause()
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                view.finish()
            }
        }
    }

    // 현재 위치 마커: 최초 1회 생성 후에는 재생성하지 않고 위치만 이동시킨다.
    LaunchedEffect(kakaoMap, currentPosition) {
        val map = kakaoMap ?: return@LaunchedEffect
        val position = currentPosition ?: return@LaunchedEffect
        val latLng = LatLng.from(position.coordinate.latitude, position.coordinate.longitude)

        val label = currentLocationLabel
        if (label == null) {
            val styles = map.labelManager?.addLabelStyles(
                LabelStyles.from(LabelStyle.from(R.drawable.ic_map_current_location))
            )
            currentLocationLabel = map.labelManager?.layer?.addLabel(
                LabelOptions.from(latLng).setStyles(styles)
            )
            map.moveCamera(CameraUpdateFactory.newCenterPosition(latLng, DEFAULT_ZOOM_LEVEL))
        } else {
            label.moveTo(latLng)
            map.moveCamera(CameraUpdateFactory.newCenterPosition(latLng))
        }
    }

    // 경로 폴리라인: route 참조가 바뀔 때(신규 안내 시작/재탐색)만 다시 그린다.
    LaunchedEffect(kakaoMap, route) {
        val map = kakaoMap ?: return@LaunchedEffect
        if (route === drawnRoute) return@LaunchedEffect

        map.routeLineManager?.layer?.removeAll()
        currentRouteLine = null

        if (route != null && route.waypoints.size >= 2) {
            val points = route.waypoints.map { LatLng.from(it.lat, it.lng) }
            val stylesSet = RouteLineStylesSet.from(
                "route",
                RouteLineStyles.from(RouteLineStyle.from(ROUTE_LINE_WIDTH, ROUTE_LINE_COLOR))
            )
            val segment = RouteLineSegment.from(points).setStyles(stylesSet.getStyles(0))
            currentRouteLine = map.routeLineManager?.layer?.addRouteLine(
                RouteLineOptions.from(segment).setStylesSet(stylesSet)
            )
        }
        drawnRoute = route
    }

    DisposableEffect(Unit) {
        onDispose {
            currentLocationLabel = null
            currentRouteLine = null
        }
    }
}

private const val TAG = "KakaoMapView"
private const val DEFAULT_ZOOM_LEVEL = 17
private const val ROUTE_LINE_WIDTH = 16f
private val ROUTE_LINE_COLOR = Color.parseColor("#4285F4")
