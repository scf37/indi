Indi is not DI.

###Design principles

- Separate effect to describe application initialization
- Resource-aware
- Lazyness - only required dependencies are instantiated
- Memoization - every dependency instantiated only once
- Compile-time, dependency resolution should always succeed
- Mockable - any dependency can be replaced with stub
- Parallel - can start independent submodules in parallel

###Requirements

- constructed application as a tree of case classes
- ability to instantiate any service w/o starting everything else
- ability to mock any service
- Resource support
