part of '../extensions.dart';

extension BuildContextExtension on BuildContext {
  MediaQueryData get mediaQuery => MediaQuery.of(this);
  ThemeData get theme => Theme.of(this);

  Size get size => mediaQuery.size;
  double get height => size.height;
  double get width => size.width;

  double get personalInfoTextFieldMinimumWidth => 500;

  double get personalInfoTextFieldSmallMinimumWidth => 245;

  bool get tooSmall => width < 350 || height < 500;
  bool get mobileWidth => width <= 2000;
  bool get tabletWidth => width > 600 && width <= 900;
  TextTheme get text => theme.textTheme;

  bool get isAndroid => theme.platform == TargetPlatform.android;
  bool get isIOS => theme.platform == TargetPlatform.iOS;
  bool get isWindows => theme.platform == TargetPlatform.windows;
  bool get isLinux => theme.platform == TargetPlatform.linux;
  bool get isMacOS => theme.platform == TargetPlatform.macOS;
  bool get isDesktop => isWindows || isLinux || isMacOS;
  bool get isMobile => isAndroid || isIOS;

  MediaQueryData get mq => MediaQuery.of(this);
  ColorScheme get colors => theme.colorScheme;

  double get shortestSide => mq.size.shortestSide;
  double get longestSide => mq.size.longestSide;

  Orientation get orientation => mq.orientation;
  bool get isPortrait => orientation == Orientation.portrait;
  bool get isLandscape => orientation == Orientation.landscape;

  bool get isPhone => shortestSide < 600;
  bool get isTablet => shortestSide >= 600 && shortestSide < 900;
  bool get isSmallPhone => isPhone && shortestSide < 400;
  bool get isLargePhone => isPhone && shortestSide >= 400;
  bool get isSmallTablet => isTablet && shortestSide < 700;
  bool get isLargeTablet => isTablet && shortestSide >= 700;
  bool get isSmallDesktop => isDesktop && shortestSide < 1200;
  bool get isLargeDesktop => isDesktop && shortestSide >= 1200;
  bool get isSmall => isSmallPhone || isSmallTablet || isSmallDesktop;
  bool get isLarge => isLargePhone || isLargeTablet || isLargeDesktop;

  Future<T?> pushNamed<T>(String page, [Object? args]) => Navigator.of(this).pushNamed<T>(page, arguments: args);

  void showSnackBar(SnackBar snackBar) => ScaffoldMessenger.of(this).showSnackBar(snackBar);

  void showMountedSnackBar(SnackBar snackBar) => mounted
      ? ScaffoldMessenger.of(this).showSnackBar(snackBar)
      : debugPrint('Context is not mounted. SnackBar not shown.');
}

extension BuildContextNavigationExtension on BuildContext {
  Future<T?> push<T>(Widget page) => Navigator.of(this).push<T>(MaterialPageRoute(builder: (_) => page));

  Future<T?> pushReplacement<T>(Widget page) =>
      Navigator.of(this).pushReplacement<T, T?>(MaterialPageRoute(builder: (_) => page));

  Future<T?> pushAndRemoveUntil<T>(Widget page) =>
      Navigator.of(this).pushAndRemoveUntil<T>(MaterialPageRoute(builder: (_) => page), (_) => false);

  Future<T?> pushAndReplace<T>(Widget page) =>
      Navigator.of(this).pushReplacement<T, T?>(MaterialPageRoute(builder: (_) => page));

  void pop<T>([T? result]) => Navigator.of(this).pop(result);
}

enum ScreenType { mobile, desktop }
