
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_rtmp_broadcaster_example/extensions/extensions.dart';
import 'package:flutter_rtmp_broadcaster_example/score.band/helper/widgets/score.band.player.img.dart';

class ScorebandBatsmanInfo extends ConsumerWidget {
  final double height;
  final String batsmanImage;
  final String batsmanName;
  final String runs;
  final String balls;
  final bool isStrike;
  final bool mediumMode;
  final double fontSize;
  const ScorebandBatsmanInfo({
    super.key,
    required this.height,
    required this.batsmanName,
    required this.runs,
    required this.balls,
    required this.isStrike,
    required this.batsmanImage,
    required this.mediumMode,
    required this.fontSize,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final textStyleForBand = TextStyle(
      fontFamily: 'RobotoCondensed',
      color: Colors.white,
    );
    return Container(
      height: height,
      width: double.infinity,
      alignment: Alignment.center,
      child: Row(
        // mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Expanded(
            child: Row(
              children: [
                ScorebandPlayerImage(
                  imgUrl: 'https://w7.pngwing.com/pngs/1010/986/png-transparent-samuel-badree-west-indies-cricket-team-india-national-cricket-team-barrackpore-trinidad-and-tobago-cricketer-cricket-face-head-sports.png',
                  size: context.mobileWidth
                      ? 8
                      : mediumMode
                          ? 20
                          : 32,
                ),
                SizedBox(width: context.mobileWidth ? 2 : 7),
                Expanded(
                  // takes all remaining space + truncates

                  child: Text(
                    batsmanName.toUpperCase(),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: textStyleForBand.copyWith(
                      fontSize: fontSize,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
                // Text(
                //   batsmanName,
                //   style: textStyleForBand.copyWith(
                //     fontSize: fontSize,
                //     fontWeight: FontWeight.w500,
                //   ),
                // ),
              ],
            ),
          ),
          // SizedBox(
          //   width: context.mobileWidth ? 10 : 70,
          // ),
          Row(
            children: [
              isStrike
                  ? Icon(Icons.arrow_right, color: const Color(0xFF43D500), size: context.mobileWidth ? 12 : null)
                  : const SizedBox(),
              Text(
                runs,
                style: textStyleForBand.copyWith(
                  fontSize: fontSize + 2,
                  fontWeight: FontWeight.w500,
                ),
              ),
              SizedBox(width: (context.mobileWidth || mediumMode) ? 4 : 8),
              Text(
                balls,
                style: textStyleForBand.copyWith(
                  fontSize: fontSize,
                  fontWeight: FontWeight.w300,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
