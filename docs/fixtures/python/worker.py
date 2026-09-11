class Worker:
    def work(self):
        raise NotImplementedError
class Concrete(Worker):
    def work(self):
        return callee()
def callee():
    return 1
