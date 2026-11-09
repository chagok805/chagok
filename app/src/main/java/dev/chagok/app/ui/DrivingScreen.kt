package dev.chagok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The only screen while the platform restricts the display: one sentence, nothing to touch, nothing
 * moving. It reads in a glance, and the recording goes on behind it.
 */
@Composable
fun DrivingScreen(blocks: List<Block>, recording: Boolean) {
    val b = Chagok.colors
    // The shape of the running fill as the drive began, quietly: the odometer ticks on underneath, and
    // nothing here may move, not even when the first blocks of a new fill appear. The default stack when
    // the drive began with nothing yet.
    val held = remember { blocks }
    val shape = held.ifEmpty {
        listOf(7f, 15f, 31f, 47f).mapIndexed { i, km -> Block("s$i", "", km, 0f, b.self) }
    }
    DesignFrame { wide ->
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(
                Modifier.then(if (wide) Modifier.width(516.dp) else Modifier.weight(1f)).fillMaxHeight().semantics { liveRegion = LiveRegionMode.Polite },
                color = b.strong,
                padding = PaddingValues(start = 44.dp, end = 44.dp, top = 44.dp, bottom = 48.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrandMark(size = 30.dp)
                    Text("차곡차곡", style = ChagokType.body(22, FontWeight.ExtraBold, 1.2f), color = b.onStrong)
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(96.dp).clip(CircleShape).background(b.family[0].fill), contentAlignment = Alignment.Center) {
                    Icon(ChagokIcons.Wheel, null, tint = Color.White, modifier = Modifier.size(44.dp))
                }
                Heading("운전하는 동안은\n쉬어요.", 64, Modifier.padding(top = 28.dp), color = b.onStrong)
                Text(
                    "주행 중에는 화면을 쓸 수 없어요.\n차를 세우면 다시 열려요.",
                    style = ChagokType.body(20, lineHeight = 1.55f),
                    color = b.onStrongSecondary,
                    modifier = Modifier.padding(top = 18.dp),
                )
                Column(Modifier.padding(top = 40.dp)) {
                    Divider(color = Color.White.copy(alpha = 0.14f))
                    Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(b.unclassified.fill.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(b.unclassified.fill))
                        }
                        Text(
                            if (recording) "지금 달리는 길도 차곡차곡 쌓이고 있어요." else "기록을 꺼 둔 동안은 거리를 적지 않아요.",
                            style = ChagokType.body(17, FontWeight.Bold, 1.4f),
                            color = b.onStrong,
                        )
                    }
                }
            }
            if (wide) BlockSilhouette(shape, b.sunken, Modifier.weight(1f).fillMaxHeight())
        }
    }
}
