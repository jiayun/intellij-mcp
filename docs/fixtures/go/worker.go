package intelligence

type Worker interface { Work() int }
type Concrete struct {}
func (Concrete) Work() int { return Callee() }
func Callee() int { return 1 }
